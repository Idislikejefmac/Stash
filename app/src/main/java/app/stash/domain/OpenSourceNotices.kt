package app.stash.domain

/*
 * Card 26d item 13 (D78): what the "Open source notices" row in Settings lists. Each entry's text is one or more
 * plain-text files under `assets/licenses/`, shown in order, unchanged. Card 14 adds SQLCipher here instead of
 * making a second screen. Pure Kotlin.
 */

data class Notice(
    val name: String,
    /** What it is, in a few plain words. */
    val what: String,
    val licence: String,
    /** Asset paths, shown one after another. */
    val assetPaths: List<String>,
)

object OpenSourceNotices {
    const val DIR = "licenses"

    val SHERPA_ONNX = Notice(
        name = "sherpa-onnx 2.0.1",
        what = "Runs the speech model that writes out voice notes.",
        licence = "Apache License 2.0",
        assetPaths = listOf(
            "$DIR/sherpa-onnx-LICENSE.txt",
            "$DIR/sherpa-onnx-components.txt",
            "$DIR/sherpa-onnx-third-party/kaldifst-LICENSE.txt",
            "$DIR/sherpa-onnx-third-party/openfst-COPYING.txt",
            "$DIR/sherpa-onnx-third-party/simple-sentencepiece-LICENSE.txt",
            "$DIR/sherpa-onnx-third-party/utf8proc-LICENSE.md.txt",
            "$DIR/sherpa-onnx-third-party/nlohmann-json-LICENSE.MIT.txt",
            "$DIR/sherpa-onnx-third-party/hclust-cpp-LICENSE.txt",
        ),
    )

    val ONNX_RUNTIME = Notice(
        name = "ONNX Runtime",
        what = "Part of sherpa-onnx: does the model's arithmetic.",
        licence = "MIT License, Copyright (c) Microsoft Corporation",
        assetPaths = listOf("$DIR/onnxruntime-LICENSE.txt", "$DIR/onnxruntime-ThirdPartyNotices.txt"),
    )

    val MOONSHINE = Notice(
        name = "Moonshine speech models (English)",
        what = "The speech model you add in Settings.",
        licence = "MIT License, Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI)",
        assetPaths = listOf("$DIR/moonshine-LICENSE.txt"),
    )

    /** The notices for what this build contains. A build without the speech engine ships none of the three. */
    fun forBuild(speechEngineBuilt: Boolean): List<Notice> =
        if (speechEngineBuilt) listOf(SHERPA_ONNX, ONNX_RUNTIME, MOONSHINE) else emptyList()
}
