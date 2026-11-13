package app.stash.model

import app.stash.data.Confidence
import app.stash.domain.RecordKind
import app.stash.domain.ExtractedRecord
import app.stash.domain.ExtractionJson
import app.stash.domain.PictureTextRules
import app.stash.domain.RecordsFromPictures

/**
 * Card 16f: files a note that is only a picture (no words from him). Exactly one `REFERENCE` record of low confidence,
 * titled with the first two lines the phone read from the first picture, or "Picture". No rules, no model. Its record
 * is picture text, not his words ([RecordsFromPictures.isPictureText]); the extraction checks that want a quote found
 * in his text do not apply to it, because there is no text of his.
 */
object PictureOnlyExtractor {
    val name: String = RecordsFromPictures.EXTRACTOR

    fun extract(firstPictureText: String?): ExtractResult.Ok {
        val title = PictureTextRules.title(firstPictureText)
        val records = listOf(ExtractedRecord(kind = RecordKind.REFERENCE, title = title, quote = title, sure = Confidence.LOW))
        return ExtractResult.Ok(records, ExtractionJson.serialise(records))
    }
}
