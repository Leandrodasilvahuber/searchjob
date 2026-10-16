package com.huber.searchjob.engine;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Chave estável da vaga a partir da URL, para evitar candidatura duplicada entre formatos de link. */
public final class ChaveVaga {
    private static final Pattern GUPY_TOKEN = Pattern.compile("/job/([A-Za-z0-9_\\-]+=*)");
    private static final Pattern GUPY_ID = Pattern.compile("/jobs?/(\\d{5,})");
    private static final Pattern JOB_ID_JSON = Pattern.compile("\"jobId\"\\s*:\\s*(\\d+)");

    private ChaveVaga() {}

    public static String de(String url) {
        if (url == null || url.isBlank()) return "";
        if (url.contains("gupy.io")) {
            Matcher t = GUPY_TOKEN.matcher(url);
            if (t.find()) {
                try {
                    String json = new String(Base64.getUrlDecoder().decode(t.group(1).replaceAll("=+$", "")), StandardCharsets.UTF_8);
                    Matcher j = JOB_ID_JSON.matcher(json);
                    if (j.find()) return "gupy:" + j.group(1);
                } catch (IllegalArgumentException ignored) {
                    // token não decodificável: cai para a URL sem query
                }
            }
            Matcher i = GUPY_ID.matcher(url);
            if (i.find()) return "gupy:" + i.group(1);
        }
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }
}
