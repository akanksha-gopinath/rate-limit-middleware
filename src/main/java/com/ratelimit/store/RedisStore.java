package com.ratelimit.store;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.function.UnaryOperator;

public final class RedisStore implements RateLimitStore {

    private static final String LEVEL_SUFFIX = ":level";
    private static final String TIME_SUFFIX = ":time";

    private final JedisPool pool;
    private final String prefix;

    public RedisStore(JedisPool pool) {
        this(pool, "ratelimit:");
    }

    public RedisStore(JedisPool pool, String prefix) {
        this.pool = pool;
        this.prefix = prefix;
    }

    @Override
    public BucketState computeIfAbsent(String key, BucketState defaultState) {
        String levelKey = prefix + key + LEVEL_SUFFIX;
        String timeKey = prefix + key + TIME_SUFFIX;

        try (var jedis = pool.getResource()) {
            String levelStr = jedis.get(levelKey);
            if (levelStr == null) {
                jedis.set(levelKey, String.valueOf(defaultState.level()));
                jedis.set(timeKey, String.valueOf(defaultState.lastUpdateNanos()));
                return defaultState;
            }
            double level = Double.parseDouble(levelStr);
            long time = Long.parseLong(jedis.get(timeKey));
            return new BucketState(level, time);
        }
    }

    @Override
    public BucketState updateAtomically(String key, BucketState defaultState, UnaryOperator<BucketState> updater) {
        String levelKey = prefix + key + LEVEL_SUFFIX;
        String timeKey = prefix + key + TIME_SUFFIX;

        String luaScript = """
                local levelKey = KEYS[1]
                local timeKey = KEYS[2]
                local defaultLevel = tonumber(ARGV[1])
                local defaultTime = ARGV[2]

                local currentLevel = redis.call('GET', levelKey)
                local currentTime = redis.call('GET', timeKey)

                if currentLevel == false then
                    currentLevel = defaultLevel
                    currentTime = defaultTime
                else
                    currentLevel = tonumber(currentLevel)
                    currentTime = currentTime
                end

                -- Return current state; actual computation happens client-side
                -- then we do a SET. For true atomicity we use WATCH/MULTI.
                return {tostring(currentLevel), currentTime}
                """;

        try (var jedis = pool.getResource()) {
            jedis.watch(levelKey, timeKey);

            String levelStr = jedis.get(levelKey);
            BucketState current;
            if (levelStr == null) {
                current = defaultState;
            } else {
                double level = Double.parseDouble(levelStr);
                long time = Long.parseLong(jedis.get(timeKey));
                current = new BucketState(level, time);
            }

            BucketState updated = updater.apply(current);

            var tx = jedis.multi();
            tx.set(levelKey, String.valueOf(updated.level()));
            tx.set(timeKey, String.valueOf(updated.lastUpdateNanos()));
            var results = tx.exec();

            if (results == null || results.isEmpty()) {
                // Optimistic lock failed — retry
                jedis.unwatch();
                return updateAtomically(key, defaultState, updater);
            }

            return updated;
        }
    }
}
