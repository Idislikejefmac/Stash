package app.stash

import app.stash.domain.CorrectionLearner
import app.stash.domain.Corrector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectorTest {
    private val vocab = listOf("Syd", "Costco", "frunk", "Frank", "Tesla")
    private val c = Corrector(vocab, mapOf("sid" to "Syd"))

    @Test fun sidBecomesSyd() = assertEquals("I told Syd about it", c.correct("I told Sid about it"))
    @Test fun lowercaseSidAtStart() = assertEquals("Syd has the keys", c.correct("sid has the keys"))
    @Test fun shoutedKeepsShouting() = assertEquals("SYD", c.correct("SID"))
    @Test fun possessiveOfSidIsHandled() = assertEquals("Syd's glasses", c.correct("Sid's glasses"))
    @Test fun otherApostropheWordsUntouched() =
        assertEquals("can't", Corrector(vocab, mapOf("can" to "Kan")).correct("can't"))
    @Test fun wholeWordOnly() {
        assertEquals("inside the side drawer", c.correct("inside the side drawer"))
        assertEquals("Sidney is here", c.correct("Sidney is here"))
        assertEquals("residual", c.correct("residual"))
    }
    @Test fun multipleOccurrences() = assertEquals("Syd and Syd", c.correct("sid and Sid"))
    @Test fun ruleReplacementKeepsMeantSpelling() {
        val r = Corrector(vocab, mapOf("front" to "frunk"))
        assertEquals("It is in the frunk", r.correct("It is in the front"))
        assertEquals("Frunk is full", r.correct("Front is full"))
    }
    @Test fun phraseRule() {
        val r = Corrector(vocab, mapOf("cost co" to "Costco"))
        assertEquals("buy milk at Costco", r.correct("buy milk at cost co"))
    }
    @Test fun longerRuleWinsOverShorter() {
        val r = Corrector(emptyList(), mapOf("cost" to "price", "cost co" to "Costco"))
        assertEquals("Costco price", r.correct("cost co cost"))
    }
    @Test fun fuzzyFixesOneLetterSlip() {
        assertEquals("buy milk at Costco", c.correct("buy milk at Costko"))
        assertEquals("a Tesla", c.correct("a Tesle"))
        assertEquals("Frank", Corrector(listOf("Frank"), emptyMap()).correct("Frenk"))
        assertEquals("Frenk", c.correct("Frenk")) // close to both Frank and frunk: left alone
    }
    @Test fun fuzzyDoesNotTouchOrdinaryWords() {
        val text = "I put the trunk of the truck in the blank storage, thanks Franks, tests cost least, Frankly"
        assertEquals(text, c.correct(text))
    }
    @Test fun fuzzyIgnoresShortWords() = assertEquals("Sod and Syd", Corrector(vocab, mapOf("sid" to "Syd")).correct("Sod and Sid"))
    @Test fun fuzzyRequiresSameFirstLetter() = assertEquals("a Zesla", c.correct("a Zesla"))
    @Test fun exactVocabularyWordUntouched() = assertEquals("Tesla frunk Costco", c.correct("Tesla frunk Costco"))
    @Test fun capitaliseNameAtSentenceStartFromFuzzy() = assertEquals("Costco is closed", c.correct("Costko is closed"))
    @Test fun emptyAndPunctuation() {
        assertEquals("", c.correct(""))
        assertEquals("Hi, Syd!", c.correct("Hi, Sid!"))
    }
    @Test fun noVocabularyNoChange() = assertEquals("Sid", Corrector(emptyList(), emptyMap()).correct("Sid"))

    // ---- learning from an edit ----
    @Test fun learnsSingleWordChange() {
        assertEquals(listOf("sid" to "Syd"), CorrectionLearner.learn("Sid has my keys", "Syd has my keys"))
    }
    @Test fun learnsFromMiddleOfSentence() {
        assertEquals(listOf("front" to "frunk"), CorrectionLearner.learn("Put it in the front, ok.", "Put it in the frunk, ok."))
    }
    @Test fun learnsShortPhrase() {
        assertEquals(listOf("cost co" to "Costco"), CorrectionLearner.learn("buy at cost co", "buy at Costco"))
    }
    @Test fun caseOnlyChangeLearnsNothing() = assertTrue(CorrectionLearner.learn("buy at costco", "buy at Costco").isEmpty())
    @Test fun noChangeLearnsNothing() = assertTrue(CorrectionLearner.learn("same text", "same text").isEmpty())
    @Test fun bigRewriteLearnsNothing() =
        assertTrue(CorrectionLearner.learn("one two three four five", "alpha beta gamma delta epsilon").isEmpty())
    @Test fun pureDeletionLearnsNothing() = assertTrue(CorrectionLearner.learn("a b c", "a c").isEmpty())

    @Test fun replaceWordKeepsOtherWords() {
        assertEquals("Syd said inside is fine, Syd.", CorrectionLearner.replaceWord("Sid said inside is fine, sid.", "sid", "Syd"))
    }
    @Test fun learnedRuleThenCorrects() {
        val rules = CorrectionLearner.learn("my frunk is full of Frank's stuff and a front", "my frunk is full of Frank's stuff and a frunk")
            .toMap()
        assertEquals("a frunk", Corrector(vocab, rules).correct("a front"))
    }
}
