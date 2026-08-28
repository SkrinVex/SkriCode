package su.SkrinVex.SkriCode.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class SoundManager(
    private val ctx: Context,
    private val getSoundFile: (soundName: String) -> File?
) {
    private val tag = "SoundManager"

    // --- SoundPool для коротких эффектов (выстрелы, прыжки, щелчки) ---
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var soundPool: SoundPool? = SoundPool.Builder()
        .setMaxStreams(16)
        .setAudioAttributes(audioAttributes)
        .build()

    private val soundIdMap = ConcurrentHashMap<String, Int>()
    private val loadedSoundIds = ConcurrentHashMap<Int, Boolean>()
    private val activeStreams = ConcurrentHashMap<String, MutableSet<Int>>()

    private data class PendingPlay(
        val name: String,
        val soundId: Int,
        val volume: Float,
        val loop: Boolean,
        val rate: Float
    )
    private val pendingQueue = ConcurrentLinkedQueue<PendingPlay>()
    @Volatile
    private var isPaused: Boolean = false

    init {
        soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loadedSoundIds[sampleId] = true
                // Воспроизводим отложенные запросы для этого sampleId (если не на паузе)
                val iter = pendingQueue.iterator()
                while (iter.hasNext()) {
                    val req = iter.next()
                    if (req.soundId == sampleId) {
                        iter.remove()
                        if (!isPaused) {
                            playLoadedSound(req.name, req.soundId, req.volume, req.loop, req.rate)
                        }
                    }
                }
            }
        }
    }

    /**
     * Воспроизвести короткий звуковой эффект через SoundPool.
     */
    fun playSound(name: String, volume: Float = 1f, loop: Boolean = false, rate: Float = 1f): Int {
        if (isPaused) return 0
        val sp = soundPool ?: return 0
        val clampedVol = volume.coerceIn(0f, 1f)
        val clampedRate = rate.coerceIn(0.5f, 2.0f)

        val soundId = soundIdMap[name]
        if (soundId != null) {
            if (loadedSoundIds[soundId] == true) {
                return playLoadedSound(name, soundId, clampedVol, loop, clampedRate)
            } else {
                pendingQueue.add(PendingPlay(name, soundId, clampedVol, loop, clampedRate))
                return 0
            }
        }

        // Загружаем файл
        val file = getSoundFile(name) ?: return 0
        if (!file.exists()) return 0

        try {
            val newSoundId = sp.load(file.absolutePath, 1)
            soundIdMap[name] = newSoundId
            pendingQueue.add(PendingPlay(name, newSoundId, clampedVol, loop, clampedRate))
        } catch (e: Exception) {
            Log.e(tag, "Ошибка загрузки звука $name: ${e.message}")
        }
        return 0
    }

    private fun playLoadedSound(name: String, soundId: Int, volume: Float, loop: Boolean, rate: Float): Int {
        val sp = soundPool ?: return 0
        val loopParam = if (loop) -1 else 0
        val streamId = sp.play(soundId, volume, volume, 1, loopParam, rate)
        if (streamId != 0) {
            activeStreams.computeIfAbsent(name) { ConcurrentHashMap.newKeySet() }.add(streamId)
        }
        return streamId
    }

    /**
     * Остановить все активные потоки конкретного звука.
     */
    fun stopSound(name: String) {
        val sp = soundPool ?: return
        val streams = activeStreams.remove(name) ?: return
        for (sid in streams) {
            sp.stop(sid)
        }
    }

    /**
     * Остановить все воспроизводящиеся звуковые эффекты.
     */
    fun stopAllSounds() {
        val sp = soundPool ?: return
        for ((_, streams) in activeStreams) {
            for (sid in streams) {
                sp.stop(sid)
            }
        }
        activeStreams.clear()
        pendingQueue.clear()
    }

    // --- MediaPlayer для длинной фоновой музыки (потоковое воспроизведение) ---
    private var mediaPlayer: MediaPlayer? = null
    private var currentMusicName: String? = null
    private var isMusicPaused: Boolean = false
    private var musicVolume: Float = 1f

    /**
     * Воспроизвести фоновую музыку через MediaPlayer со стримингом из файла.
     */
    @Synchronized
    fun playMusic(name: String, volume: Float = 1f, loop: Boolean = true) {
        val clampedVol = volume.coerceIn(0f, 1f)
        musicVolume = clampedVol

        if (currentMusicName == name && mediaPlayer != null) {
            if (isMusicPaused && !isPaused) {
                resumeMusic()
            }
            mediaPlayer?.setVolume(clampedVol, clampedVol)
            mediaPlayer?.isLooping = loop
            return
        }

        stopMusic()

        val file = getSoundFile(name) ?: return
        if (!file.exists()) return

        try {
            val player = MediaPlayer()
            FileInputStream(file).use { fis ->
                player.setDataSource(fis.fd)
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.isLooping = loop
            player.setVolume(clampedVol, clampedVol)
            player.prepare()
            if (!isPaused) {
                player.start()
                isMusicPaused = false
            } else {
                isMusicPaused = true
            }

            mediaPlayer = player
            currentMusicName = name

            player.setOnCompletionListener {
                if (!loop) {
                    currentMusicName = null
                    isMusicPaused = false
                }
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e(tag, "MediaPlayer error: what=$what, extra=$extra")
                stopMusic()
                true
            }
        } catch (e: Exception) {
            Log.e(tag, "Ошибка воспроизведения музыки $name: ${e.message}")
            stopMusic()
        }
    }

    @Synchronized
    fun pauseMusic() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isMusicPaused = true
            }
        }
    }

    @Synchronized
    fun resumeMusic() {
        if (isPaused) return
        mediaPlayer?.let {
            if (isMusicPaused) {
                it.start()
                isMusicPaused = false
            }
        }
    }

    @Synchronized
    fun stopMusic() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        currentMusicName = null
        isMusicPaused = false
    }

    @Synchronized
    fun setMusicVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        musicVolume = clamped
        mediaPlayer?.setVolume(clamped, clamped)
    }

    fun isMusicPlaying(): Boolean = mediaPlayer?.isPlaying == true

    /**
     * Поставить на паузу все звуки и музыку (например при сворачивании приложения).
     */
    @Synchronized
    fun pauseAll() {
        isPaused = true
        soundPool?.autoPause()
        pauseMusic()
    }

    /**
     * Возобновить после паузы.
     */
    @Synchronized
    fun resumeAll() {
        isPaused = false
        soundPool?.autoResume()
        resumeMusic()
    }

    /**
     * Полная очистка и освобождение ресурсов при выходе из симуляции/активности.
     */
    @Synchronized
    fun release() {
        stopAllSounds()
        stopMusic()
        try {
            soundPool?.release()
        } catch (_: Exception) {}
        soundPool = null
        soundIdMap.clear()
        loadedSoundIds.clear()
    }
}
