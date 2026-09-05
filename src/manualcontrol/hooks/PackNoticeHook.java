package manualcontrol.hooks;

import manualcontrol.Logger;
import manualcontrol.reflect.BCUFields;

import java.util.LinkedHashSet;
import java.util.Set;

public final class PackNoticeHook {

    private static final Set<String> NOTICES = new LinkedHashSet<String>();
    private static final int MAX_NOTICES = 40;

    private PackNoticeHook() {}

    public static boolean onErrOnce(String text, String title) {
        try {
            if (text == null || !isPackNotice(text)) return false;
            synchronized (NOTICES) {
                if (NOTICES.size() < MAX_NOTICES) NOTICES.add(text.trim());
            }
            Logger.log("PackNotice captured (popup suppressed): " + text);
            return true;
        } catch (Throwable t) {

            return false;
        }
    }

    private static boolean isPackNotice(String text) {
        String t = text.toLowerCase();

        return t.contains("pack") || t.contains("parent pack")
                || t.contains("parent") || t.contains("requires");
    }

    public static void afterRefrTips(Object mainPage) {
        try {
            if (mainPage == null) return;
            String noticeHtml;
            synchronized (NOTICES) {
                if (NOTICES.isEmpty()) return;
                noticeHtml = buildHtml();
            }
            Object tipsObj = BCUFields.get(mainPage, "tips");
            if (!(tipsObj instanceof javax.swing.JLabel)) return;
            javax.swing.JLabel tips = (javax.swing.JLabel) tipsObj;
            String inner = stripHtmlWrapper(tips.getText());
            tips.setText("<html>" + inner + "<hr>" + noticeHtml + "</html>");
        } catch (Throwable t) {
            Logger.err("PackNotice afterRefrTips failed", t);
        }
    }

    private static String buildHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='color:#c00000'><b>Pack load notes (")
          .append(NOTICES.size()).append("):</b><br>");
        for (String n : NOTICES) {
            sb.append("&bull; ").append(escape(n)).append("<br>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String stripHtmlWrapper(String s) {
        if (s == null) return "";
        String t = s.trim();
        String low = t.toLowerCase();
        if (low.startsWith("<html>")) t = t.substring(6);
        low = t.toLowerCase();
        if (low.endsWith("</html>")) t = t.substring(0, t.length() - 7);
        return t;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
