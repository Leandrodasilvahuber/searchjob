package com.huber.searchjob.engine;

import org.springframework.web.util.HtmlUtils;

final class Html {
    private Html() {}

    /** Remove tags, resolve entidades e colapsa espaços. */
    static String texto(String html) {
        if (html == null) return "";
        return HtmlUtils.htmlUnescape(html.replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ").trim();
    }
}
