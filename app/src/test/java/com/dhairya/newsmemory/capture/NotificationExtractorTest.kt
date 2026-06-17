package com.dhairya.newsmemory.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationExtractorTest {

    private fun input(
        pkg: String = "com.app.news",
        appLabel: String = "NewsApp",
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        subText: String? = null,
        lines: List<String> = emptyList(),
        template: String? = null
    ) = NotificationExtractor.Input(
        packageName = pkg, appLabel = appLabel, title = title, text = text,
        bigText = bigText, subText = subText, infoText = null, summaryText = null,
        textLines = lines, template = template
    )

    // Ground truth from the Block screenshots.

    @Test
    fun `google news aggregator puts publisher in title and headline in text`() {
        val items = NotificationExtractor.extract(
            input(
                pkg = "com.google.android.apps.magazines",
                appLabel = "Google News",
                title = "Time Magazine",
                text = "Pope Leo Uses First Major Papal Text to Warn About Dangers of AI"
            )
        )
        assertEquals(1, items.size)
        assertEquals("Pope Leo Uses First Major Papal Text to Warn About Dangers of AI", items[0].title)
        assertEquals("Time Magazine", items[0].publisher)
        assertEquals(ParseQuality.FULL, items[0].quality)
    }

    @Test
    fun `the verge style no longer surfaces the publisher as the headline`() {
        val items = NotificationExtractor.extract(
            input(
                pkg = "com.google.android.apps.magazines",
                appLabel = "Google News",
                title = "The Verge",
                text = "Why Stenography jobs continue to thrive in the age of AI"
            )
        )
        assertEquals("Why Stenography jobs continue to thrive in the age of AI", items[0].title)
        assertEquals("The Verge", items[0].publisher)
    }

    @Test
    fun `standard bigtext keeps title as headline and bigtext as snippet`() {
        val items = NotificationExtractor.extract(
            input(
                pkg = "com.moneycontrol",
                appLabel = "Moneycontrol",
                title = "Asian stocks pared gains",
                text = "GIFT Nifty falls, signals muted start",
                bigText = "GIFT Nifty falls, signals muted start for Sensex, Nifty as US strikes in Iran"
            )
        )
        assertEquals("Asian stocks pared gains", items[0].title)
        assertEquals(
            "GIFT Nifty falls, signals muted start for Sensex, Nifty as US strikes in Iran",
            items[0].snippet
        )
        assertEquals("Moneycontrol", items[0].publisher)   // falls back to app label
    }

    @Test
    fun `chrome style title headline with url snippet`() {
        val items = NotificationExtractor.extract(
            input(
                pkg = "com.android.chrome",
                appLabel = "Chrome",
                title = "Beef Prices Are Slaughtering American Steakhouses",
                text = "These famous restaurants now face bankruptcy. www.forbes.com"
            )
        )
        assertEquals("Beef Prices Are Slaughtering American Steakhouses", items[0].title)
        assertEquals("These famous restaurants now face bankruptcy. www.forbes.com", items[0].snippet)
    }

    @Test
    fun `inbox multi-line bundle splits into one story per line`() {
        val items = NotificationExtractor.extract(
            input(
                pkg = "com.google.android.apps.magazines",
                appLabel = "Google News",
                title = "Top stories",
                subText = "Google News",
                lines = listOf(
                    "Sensex slips below 81,000",
                    "RBI holds repo rate steady",
                    "Anthropic in talks to raise at \$180B"
                ),
                template = "android.app.Notification\$InboxStyle"
            )
        )
        assertEquals(3, items.size)
        assertEquals("Sensex slips below 81,000", items[0].title)
        assertEquals("Anthropic in talks to raise at \$180B", items[2].title)
        assertTrue(items.all { it.quality == ParseQuality.FULL })
    }

    @Test
    fun `inshorts puts app name in title and headline in text`() {
        // Real Inshorts payload from the live dump
        val items = NotificationExtractor.extract(
            input(
                pkg = "com.nis.app", appLabel = "Inshorts", title = "inshorts",
                text = "Mbappe shows off new celebration after scoring his 1st goal at FIFA World Cup 2026"
            )
        )
        assertEquals(1, items.size)
        assertEquals(
            "Mbappe shows off new celebration after scoring his 1st goal at FIFA World Cup 2026",
            items[0].title
        )
        assertEquals(ParseQuality.FULL, items[0].quality)
    }

    @Test
    fun `inshorts with only the app name and no body is unparseable`() {
        val items = NotificationExtractor.extract(
            input(pkg = "com.nis.app", appLabel = "Inshorts", title = "inshorts")
        )
        assertEquals(1, items.size)
        assertEquals(ParseQuality.UNPARSEABLE, items[0].quality)
    }

    @Test
    fun `dailyhunt null title puts headline in text`() {
        // Real Dailyhunt (com.eterno) payload: title null, headline in text
        val items = NotificationExtractor.extract(
            input(
                pkg = "com.eterno", appLabel = "Dailyhunt", title = null,
                text = "US drops 'Indo' from Indo-Pacific Command, sparks row over India map"
            )
        )
        assertEquals("US drops 'Indo' from Indo-Pacific Command, sparks row over India map", items[0].title)
        assertEquals(ParseQuality.FULL, items[0].quality)
    }

    @Test
    fun `google news generic placeholder is filtered as junk`() {
        val items = NotificationExtractor.extract(
            input(
                pkg = "com.google.android.apps.magazines", appLabel = "Google News",
                title = "Google News", text = "You have a notification"
            )
        )
        assertEquals(ParseQuality.UNPARSEABLE, items[0].quality)
    }

    @Test
    fun `snippet is dropped when it merely repeats the headline`() {
        val items = NotificationExtractor.extract(
            input(
                pkg = "com.app.news", appLabel = "NewsApp",
                title = "RBI holds repo rate", text = "RBI holds repo rate"
            )
        )
        assertNull(items[0].snippet)
    }

    @Test
    fun `aggregator with no body is unparseable, never the publisher as headline`() {
        val items = NotificationExtractor.extract(
            input(
                pkg = "com.google.android.apps.magazines",
                appLabel = "Google News",
                title = "The Verge"   // publisher only, no headline text
            )
        )
        // We must not fabricate a headline from the publisher name
        assertEquals(ParseQuality.UNPARSEABLE, items[0].quality)
        assertEquals("The Verge", items[0].publisher)
    }
}
