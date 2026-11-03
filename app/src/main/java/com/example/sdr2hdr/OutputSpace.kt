package com.example.sdr2hdr

/** Output colour spaces. shaderMode is read by the fragment shader (uMode). */
enum class OutputSpace(val label: String, val shaderMode: Int) {
    HDR10_PQ("HDR10 (PQ, BT.2020)", 2),
    HLG("HLG (BT.2020)", 1),
    SDR_709("SDR (BT.709)", 0)
}
