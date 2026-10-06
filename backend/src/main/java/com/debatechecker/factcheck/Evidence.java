package com.debatechecker.factcheck;

/**
 * Something a verdict can rest on and the user can open.
 *
 * @param source "Google Fact Check", "Web" or "Wikipedia"
 * @param title  the publisher and its headline ("PolitiFact: ..."), the site, date and headline of a
 *               web page ("apnews.com, 2016-02-26: ..."), or the Wikipedia page title
 * @param text   the claim a fact-checker reviewed, or a passage from the page
 * @param rating the fact-checker's own rating ("Mostly False"); "" for a Wikipedia passage
 */
public record Evidence(String source, String title, String url, String text, String rating) {}
