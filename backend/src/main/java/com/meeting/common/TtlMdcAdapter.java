package com.meeting.common;

import com.alibaba.ttl.TransmittableThreadLocal;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

public final class TtlMdcAdapter {

    private static final TransmittableThreadLocal<Map<String, String>> TTL = new TransmittableThreadLocal<>();

    private TtlMdcAdapter() {}

    public static void setTraceId(String traceId) {
        Map<String, String> map = TTL.get();
        if (map == null) {
            map = new HashMap<>();
            TTL.set(map);
        }
        map.put("traceId", traceId);
        MDC.put("traceId", traceId);
    }

    public static void setLayer(String layer) {
        Map<String, String> map = TTL.get();
        if (map == null) {
            map = new HashMap<>();
            TTL.set(map);
        }
        map.put("layer", layer);
        MDC.put("layer", layer);
    }

    public static void remove(String key) {
        Map<String, String> map = TTL.get();
        if (map != null) {
            map.remove(key);
        }
        MDC.remove(key);
    }

    public static void clear() {
        TTL.remove();
        MDC.clear();
    }

    public static Runnable decorate(Runnable runnable) {
        Map<String, String> context = TTL.get();
        return () -> {
            try {
                if (context != null) {
                    TTL.set(context);
                    for (Map.Entry<String, String> entry : context.entrySet()) {
                        MDC.put(entry.getKey(), entry.getValue());
                    }
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
