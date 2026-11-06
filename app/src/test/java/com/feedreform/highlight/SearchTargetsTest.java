package com.feedreform.highlight;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 平台搜索 scheme：无关键词直达搜索页，有（已编码）关键词直达结果页。 */
public class SearchTargetsTest {

    @Test
    public void emptyKeywordOpensSearchPage() {
        assertEquals("xhsdiscover://search/result", SearchTargets.uri("xhs", ""));
        assertEquals("bilibili://search", SearchTargets.uri("bili", ""));
    }

    @Test
    public void nullKeywordIsTreatedAsEmpty() {
        assertEquals("xhsdiscover://search/result", SearchTargets.uri("xhs", null));
    }

    @Test
    public void keywordIsAppendedVerbatim() {
        assertEquals("xhsdiscover://search/result?keyword=iphone15",
                SearchTargets.uri("xhs", "iphone15"));
        assertEquals("bilibili://search?keyword=switch",
                SearchTargets.uri("bili", "switch"));
    }

    @Test
    public void alreadyEncodedKeywordIsNotDoubleEncoded() {
        assertEquals("xhsdiscover://search/result?keyword=%E5%B1%8F%E5%B9%95",
                SearchTargets.uri("xhs", "%E5%B1%8F%E5%B9%95"));
        assertEquals("bilibili://search?keyword=a%20b", SearchTargets.uri("bili", "a%20b"));
    }

    @Test
    public void unknownSourceFallsBackToXhs() {
        assertEquals("xhsdiscover://search/result", SearchTargets.uri("other", ""));
        assertEquals("xhsdiscover://search/result?keyword=x", SearchTargets.uri(null, "x"));
    }
}
