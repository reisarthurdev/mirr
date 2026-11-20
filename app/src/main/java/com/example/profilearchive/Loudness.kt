@file:OptIn(UnstableApi::class)

package com.example.profilearchive

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Volume de cada arquivo, medido uma vez decodificando o áudio (até 2 min) e guardado em
 * .dados/<arquivo>.loud. A medida é um RMS com "portão" parecido com o LUFS: trechos de silêncio
 * e trechos bem mais baixos que a média não puxam o valor para baixo.
 */
object Loudness {
    /** Volume alvo (RMS ≈ -18 dBFS) e limites do ganho (-12 dB a +12 dB). */
    private const val TARGET = 0.125f
    private const val MIN_GAIN = 0.25f
    private const val MAX_GAIN = 4f
    /** Abaixo disso (≈ -55 dBFS) o arquivo é considerado sem som. */
    private const val SILENT = 0.0018f
    private const val MAX_SECONDS = 120

    private val cache = ConcurrentHashMap<String, Float>()      // caminho → RMS (0 = sem áudio)

    private fun cacheFile(path: String): File { val f = File(path); return File(metaDir(f.parentFile!!), f.name + ".loud") }

    /** RMS já conhecido, sem medir (null = ainda não medido). */
    fun known(path: String): Float? = cache[path] ?: runCatching { cacheFile(path).readText().trim().toFloat() }.getOrNull()
        ?.also { cache[path] = it }

    /** RMS do arquivo; mede se ainda não souber. Bloqueia: chame fora da thread principal. */
    fun rms(path: String): Float = known(path) ?: measure(path).also { v ->
        cache[path] = v
        runCatching { cacheFile(path).writeText(v.toString()) }
    }

    fun gainOf(rms: Float): Float = if (rms <= SILENT) 1f else (TARGET / rms).coerceIn(MIN_GAIN, MAX_GAIN)
    fun hasSound(path: String): Boolean = rms(path) > SILENT

    private fun measure(path: String): Float {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return 0f
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val blocks = ArrayList<Double>()                 // média dos quadrados por bloco de 400 ms
            var blockSum = 0.0; var blockN = 0; var total = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var stalls = 0
            fun add(s: Float) {
                blockSum += s * s; blockN++
                if (blockN >= rate * channels * 2 / 5) { blocks += blockSum / blockN; blockSum = 0.0; blockN = 0 }
            }
            while (total < rate.toLong() * channels * MAX_SECONDS && stalls < 200) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> c.outputFormat.let { f ->
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        stalls = 0
                        val buf = c.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }.order(ByteOrder.nativeOrder())
                        if (float) { val fb = buf.asFloatBuffer(); while (fb.hasRemaining()) add(fb.get()); total += fb.capacity() }
                        else { val sb = buf.asShortBuffer(); while (sb.hasRemaining()) add(sb.get() / 32768f); total += sb.capacity() }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> stalls++
                }
            }
            if (blockN > 0) blocks += blockSum / blockN
            // portão absoluto (-60 dBFS) e depois relativo (10 dB abaixo da média do que sobrou)
            val abs = blocks.filter { it > 1e-6 }
            if (abs.isEmpty()) return 0f
            val mean = abs.average()
            val rel = abs.filter { it > mean / 10.0 }.ifEmpty { abs }
            return sqrt(rel.average()).toFloat()
        } catch (e: Exception) {
            return 0f
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; ex.release()
        }
    }
}

/**
 * Aplica um ganho no áudio do player (para o volume ficar igual entre vídeos). Ganho acima de 1
 * passa por um limitador suave, para não estourar. O ganho muda em rampa curta, sem estalo.
 */
class GainProcessor : BaseAudioProcessor() {
    @Volatile var gain = 1f
    private var current = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val target = gain
        val inS = inputBuffer.order(ByteOrder.nativeOrder())
        while (inputBuffer.remaining() >= 2) {
            current += (target - current) * 0.0005f
            var y = inS.short / 32768f * current
            val a = abs(y)
            if (a > KNEE) y = (if (y < 0) -1 else 1) * (KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE)))
            out.putShort((y * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }

    private companion object { const val KNEE = 0.85f }
}
