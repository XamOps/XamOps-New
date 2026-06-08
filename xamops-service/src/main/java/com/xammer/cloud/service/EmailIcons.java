package com.xammer.cloud.service;

/**
 * Inline SVG icons for XamOps email templates.
 * All icons use currentColor — set color on a parent element to control fill.
 */
public final class EmailIcons {

    private EmailIcons() {}

    private static String svg(int px, String paths) {
        return "<svg style='display:inline-block;vertical-align:middle;' width='" + px + "' height='" + px +
               "' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2'" +
               " stroke-linecap='round' stroke-linejoin='round' xmlns='http://www.w3.org/2000/svg'>" +
               paths + "</svg>";
    }

    private static String svgFilled(int px, String paths) {
        return "<svg style='display:inline-block;vertical-align:middle;' width='" + px + "' height='" + px +
               "' viewBox='0 0 24 24' fill='currentColor' stroke='none' xmlns='http://www.w3.org/2000/svg'>" +
               paths + "</svg>";
    }

    /** Warning triangle with exclamation */
    public static String warning(int px) {
        return svg(px,
                "<path d='M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z'/>" +
                "<line x1='12' y1='9' x2='12' y2='13'/>" +
                "<line x1='12' y1='17' x2='12.01' y2='17'/>");
    }

    /** Circle with exclamation */
    public static String alertCircle(int px) {
        return svg(px,
                "<circle cx='12' cy='12' r='10'/>" +
                "<line x1='12' y1='8' x2='12' y2='12'/>" +
                "<line x1='12' y1='16' x2='12.01' y2='16'/>");
    }

    /** Shield — security */
    public static String shield(int px) {
        return svg(px, "<path d='M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z'/>");
    }

    /** Bar chart — quota / metrics */
    public static String barChart(int px) {
        return svg(px,
                "<line x1='18' y1='20' x2='18' y2='10'/>" +
                "<line x1='12' y1='20' x2='12' y2='4'/>" +
                "<line x1='6' y1='20' x2='6' y2='14'/>" +
                "<line x1='2' y1='20' x2='22' y2='20'/>");
    }

    /** Dollar sign — cost / anomaly / money */
    public static String dollarSign(int px) {
        return svg(px,
                "<line x1='12' y1='1' x2='12' y2='23'/>" +
                "<path d='M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6'/>");
    }

    /** Checkmark circle — success */
    public static String checkCircle(int px) {
        return svg(px,
                "<path d='M22 11.08V12a10 10 0 11-5.93-9.14'/>" +
                "<polyline points='22 4 12 14.01 9 11.01'/>");
    }

    /** Simple checkmark */
    public static String check(int px) {
        return svg(px, "<polyline points='20 6 9 17 4 12'/>");
    }

    /** Clock */
    public static String clock(int px) {
        return svg(px,
                "<circle cx='12' cy='12' r='10'/>" +
                "<polyline points='12 6 12 12 16 14'/>");
    }

    /** Rocket — launch / deploy */
    public static String rocket(int px) {
        return svg(px,
                "<path d='M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 00-2.91-.09z'/>" +
                "<path d='M12 15l-3-3a22 22 0 012-3.95A12.88 12.88 0 0122 2c0 2.72-.78 7.5-6 11a22.35 22.35 0 01-4 2z'/>" +
                "<path d='M9 12H4s.55-3.03 2-4c1.62-1.08 5 0 5 0'/>" +
                "<path d='M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5'/>");
    }

    /** Maximize / expand arrows — extend capacity */
    public static String maximize(int px) {
        return svg(px,
                "<polyline points='15 3 21 3 21 9'/>" +
                "<polyline points='9 21 3 21 3 15'/>" +
                "<line x1='21' y1='3' x2='14' y2='10'/>" +
                "<line x1='3' y1='21' x2='10' y2='14'/>");
    }

    /** Cloud */
    public static String cloud(int px) {
        return svg(px, "<path d='M18 10h-1.26A8 8 0 109 20h9a5 5 0 000-10z'/>");
    }

    /** Envelope — email */
    public static String envelope(int px) {
        return svg(px,
                "<path d='M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z'/>" +
                "<polyline points='22,6 12,13 2,6'/>");
    }

    /** Calendar */
    public static String calendar(int px) {
        return svg(px,
                "<rect x='3' y='4' width='18' height='18' rx='2' ry='2'/>" +
                "<line x1='16' y1='2' x2='16' y2='6'/>" +
                "<line x1='8' y1='2' x2='8' y2='6'/>" +
                "<line x1='3' y1='10' x2='21' y2='10'/>");
    }

    /** Trending up arrow — budget created / positive */
    public static String trendingUp(int px) {
        return svg(px,
                "<polyline points='23 6 13.5 15.5 8.5 10.5 1 18'/>" +
                "<polyline points='17 6 23 6 23 12'/>");
    }

    /** Play triangle */
    public static String play(int px) {
        return svgFilled(px, "<polygon points='5 3 19 12 5 21 5 3'/>");
    }

    /** Stop square */
    public static String stop(int px) {
        return svgFilled(px, "<rect x='4' y='4' width='16' height='16' rx='2' ry='2'/>");
    }

    /** Padlock — auth / security */
    public static String lock(int px) {
        return svg(px,
                "<rect x='3' y='11' width='18' height='11' rx='2' ry='2'/>" +
                "<path d='M7 11V7a5 5 0 0110 0v4'/>");
    }

    /** User with checkmark — welcome / onboarding */
    public static String userCheck(int px) {
        return svg(px,
                "<path d='M16 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2'/>" +
                "<circle cx='8.5' cy='7' r='4'/>" +
                "<polyline points='17 11 19 13 23 9'/>");
    }

    /** Ticket / tag — support ticket */
    public static String ticket(int px) {
        return svg(px,
                "<path d='M2 9a3 3 0 010-6h20a3 3 0 010 6'/>" +
                "<path d='M2 15a3 3 0 000 6h20a3 3 0 000-6'/>" +
                "<line x1='2' y1='12' x2='22' y2='12'/>");
    }

    /** Message square — reply / chat */
    public static String messageSquare(int px) {
        return svg(px,
                "<path d='M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z'/>");
    }

    /** File with lines — document / thread */
    public static String fileText(int px) {
        return svg(px,
                "<path d='M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z'/>" +
                "<polyline points='14 2 14 8 20 8'/>" +
                "<line x1='16' y1='13' x2='8' y2='13'/>" +
                "<line x1='16' y1='17' x2='8' y2='17'/>" +
                "<polyline points='10 9 9 9 8 9'/>");
    }

    /** Bell — reminder / notification */
    public static String bell(int px) {
        return svg(px,
                "<path d='M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9'/>" +
                "<path d='M13.73 21a2 2 0 01-3.46 0'/>");
    }

    /** Download arrow — billing / export */
    public static String download(int px) {
        return svg(px,
                "<path d='M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4'/>" +
                "<polyline points='7 10 12 15 17 10'/>" +
                "<line x1='12' y1='15' x2='12' y2='3'/>");
    }

    /** Magnifying glass — search / investigation */
    public static String search(int px) {
        return svg(px,
                "<circle cx='11' cy='11' r='8'/>" +
                "<line x1='21' y1='21' x2='16.65' y2='16.65'/>");
    }
}
