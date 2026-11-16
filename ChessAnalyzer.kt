package com.example.chesshelper

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object ChessAnalyzer {

    private const val MODEL = "claude-sonnet-5-5"

    data class Result(val fen: String, val text: String)

    /** จากภาพ JPEG -> FEN -> ตาเดินที่ดีที่สุด (เรียกจาก background thread เท่านั้น) */
    fun analyze(apiKey: String, jpeg: ByteArray, iAmWhite: Boolean): Result {
        val placement = readBoard(apiKey, jpeg, iAmWhite)
        val fen = buildFen(placement, iAmWhite)
        return Result(fen, bestMove(fen))
    }

    private fun readBoard(apiKey: String, jpeg: ByteArray, iAmWhite: Boolean): String {
        val color = if (iAmWhite) "White" else "Black"
        val prompt = "This screenshot is from a chess game app. Find the chessboard. " +
            "The user plays $color, so their pieces are normally at the bottom, but check the " +
            "piece positions and any rank/file labels to decide the real orientation. " +
            "Reply with ONLY the piece-placement field of the FEN, written from White's point of view " +
            "(rank 8 first, rank 1 last), for example rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR. " +
            "Read every square carefully. If no chessboard is visible, reply NONE."

        val image = JSONObject()
            .put("type", "image")
            .put(
                "source", JSONObject()
                    .put("type", "base64")
                    .put("media_type", "image/jpeg")
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            )
        val text = JSONObject().put("type", "text").put("text", prompt)
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 200)
            .put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", JSONArray().put(image).put(text))
                )
            )

        val resp = post(
            "https://api.anthropic.com/v1/messages",
            body.toString(),
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
        )
        val reply = JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")

        val m = Regex("([1-8pnbrqkPNBRQK]+/){7}[1-8pnbrqkPNBRQK]+").find(reply)
            ?: throw IOException("ไม่พบกระดานหมากรุกในภาพ")
        val placement = m.value
        for (rank in placement.split("/")) {
            val n = rank.sumOf { if (it.isDigit()) it - '0' else 1 }
            if (n != 8) throw IOException("AI อ่านกระดานผิดรูปแบบ ลองกดวิเคราะห์ใหม่")
        }
        return placement
    }

    private fun buildFen(placement: String, iAmWhite: Boolean): String {
        val ranks = placement.split("/").map { r ->
            r.map { if (it.isDigit()) ".".repeat(it - '0') else it.toString() }.joinToString("")
        }
        val r8 = ranks[0]
        val r1 = ranks[7]
        val sb = StringBuilder()
        if (r1[4] == 'K') {
            if (r1[7] == 'R') sb.append('K')
            if (r1[0] == 'R') sb.append('Q')
        }
        if (r8[4] == 'k') {
            if (r8[7] == 'r') sb.append('k')
            if (r8[0] == 'r') sb.append('q')
        }
        val castling = if (sb.isEmpty()) "-" else sb.toString()
        return "$placement ${if (iAmWhite) "w" else "b"} $castling - 0 1"
    }

    private fun bestMove(fen: String): String {
        val body = JSONObject().put("fen", fen).put("depth", 14)
        val j = JSONObject(post("https://chess-api.com/v1", body.toString(), emptyMap()))
        val san = j.optString("san")
        val move = j.optString("move")
        if (move.length < 4) {
            throw IOException(j.optString("text", "เอนจินไม่ตอบกลับ ตำแหน่งอาจไม่ถูกต้อง"))
        }
        val eval = j.optString("eval")
        return "แนะนำ: $san  (${move.substring(0, 2)} → ${move.substring(2, 4)})" +
            if (eval.isNotEmpty()) "\nคะแนนประเมิน: $eval" else ""
    }

    private fun post(url: String, body: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(200)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}
