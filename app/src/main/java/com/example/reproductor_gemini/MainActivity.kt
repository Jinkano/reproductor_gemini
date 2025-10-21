package com.example.reproductor_gemini

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.reproductor_gemini.databinding.ActivityMainBinding
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity()
{

    // View Binding para acceder a los elementos del layout
    private lateinit var binding: ActivityMainBinding
    // Objeto para manejar la reproducción de audio
    private var mediaPlayer: MediaPlayer? = null
    // Lista de las URIs de los archivos de audio encontrados
    private var audioList: List<Uri> = emptyList()
    // Índice de la canción que se está reproduciendo actualmente
    private var currentSongIndex: Int = -1



    // ******* Lógica del Handler y Runnable para SeekBar (Actualización en tiempo real) *******
    // Handler para programar la actualización de la barra de progreso
    private val handler = Handler(Looper.getMainLooper())

    // Runnable que actualiza la posición del SeekBar y el tiempo actual
    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
                // Actualiza la posición del SeekBar con la posición actual del MediaPlayer
                val currentPosition = it.currentPosition
                binding.seekBar.progress = currentPosition

                // Actualiza el TextView del tiempo actual
                binding.tvCurrentTime.text = formatTime(currentPosition)
            }
            // Reprograma la ejecución cada 1000 milisegundos (1 segundo)
            handler.postDelayed(this, 1000)
        }
    }
    // *****************************************************************************************


    // Define el lanzador de permisos. Este es el método moderno para pedir permisos.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Verifica si todos los permisos requeridos fueron concedidos
        val granted = permissions.entries.all { it.value }
        if (granted) {
            binding.tvStatus.text = "Permisos concedidos. Escaneando..."
            loadAudioFiles()
        } else {
            binding.tvStatus.text = "Permisos denegados. No se puede acceder a la música."
            Toast.makeText(this, "Permisos de almacenamiento necesarios para continuar.", Toast.LENGTH_LONG).show()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        //
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        //
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        /**/
        // 1. Manejo de Permisos
        checkAndRequestPermissions()


        // ******* Implementación del OnSeekBarChangeListener *******
        setupSeekBarListener()
        // *********************************************************


        // 2. Configuración de Listeners
        binding.btnScanSongs.setOnClickListener {
            // Volvemos a pedir permisos si el usuario lo pulsa (en caso de que los haya denegado antes)
            checkAndRequestPermissions()
        }

        binding.btnPlayPause.setOnClickListener {
            if (audioList.isNotEmpty() && currentSongIndex != -1) {
                togglePlayback()
            } else {
                Toast.makeText(this, "Primero escanea y carga la lista de canciones.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnNext.setOnClickListener {
            playNextSong()
        }

        binding.btnPrevious.setOnClickListener {
            playPreviousSong()
        }
    }/*fin fin fin on create*/


    /**
     * Configura el listener para manejar las interacciones del usuario con el SeekBar.
     */
    private fun setupSeekBarListener() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            // Se llama cuando el usuario comienza a arrastrar la barra
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Detenemos temporalmente las actualizaciones automáticas del Handler
                handler.removeCallbacks(updateSeekBarRunnable)
            }

            // Se llama cuando el usuario está arrastrando la barra
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Si el cambio viene del usuario, actualizamos el TextView del tiempo
                    binding.tvCurrentTime.text = formatTime(progress)
                }
            }

            // Se llama cuando el usuario deja de arrastrar la barra
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    // Reanudamos la actualización automática
                    handler.post(updateSeekBarRunnable)
                    // Movemos la reproducción de la canción a la posición seleccionada (seekTo)
                    mediaPlayer?.seekTo(it.progress)
                    // Si estaba en pausa, reanudamos la reproducción
                    if (mediaPlayer?.isPlaying == false) {
                        mediaPlayer?.start()
                        binding.btnPlayPause.text = "Pause"
                        binding.tvStatus.text = "Reproduciendo: ${getSongTitle(audioList[currentSongIndex])}"
                    }
                }
            }
        })
    }


    /**
     * Determina los permisos necesarios en función de la versión de Android
     * y solicita los permisos si no han sido concedidos.
     */
    private fun checkAndRequestPermissions() {
        // Para Android 13 (API 33) y superior
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            // Para Android 12 (API 32) y anterior
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            // Permisos ya concedidos, procede a cargar los archivos
            binding.tvStatus.text = "Permisos concedidos. Escaneando..."
            loadAudioFiles()
        } else {
            // Solicita los permisos
            binding.tvStatus.text = "Solicitando permisos..."
            requestPermissionLauncher.launch(permissions)
        }
    }


    /**
     * Consulta el ContentResolver para obtener las URIs de los archivos de audio.
     */
    private fun loadAudioFiles() {
        val tempAudioList = mutableListOf<Uri>()

        // URI de la colección de archivos de audio
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // Columnas que queremos recuperar (ID para la URI y DISPLAY_NAME para el nombre)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        // Cláusula WHERE para filtrar solo archivos de música (no ringtones, alarmas, etc.)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"

        // Ejecuta la consulta
        contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            // Cachea los índices de las columnas
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                // Obtiene el valor del ID
                val id = cursor.getLong(idColumn)
                val displayName = cursor.getString(nameColumn)

                // Construye la URI del contenido
                val contentUri: Uri = ContentUris.withAppendedId(collection, id)

                tempAudioList.add(contentUri)
                // Opcional: imprimir el nombre de la canción para depuración
                println("Canción encontrada: $displayName, URI: $contentUri")
            }
        }

        audioList = tempAudioList
        if (audioList.isNotEmpty()) {
            binding.tvStatus.text = "¡Escaneo completado! ${audioList.size} canciones encontradas."
            currentSongIndex = 0 // Establece la primera canción como actual
            updateSongInfo()
        } else {
            binding.tvStatus.text = "No se encontraron archivos de música en el dispositivo."
        }
    }

    /**
     * Inicia o pausa la reproducción del audio.
     */
    private fun togglePlayback() {
        if (mediaPlayer == null) {
            // Si el reproductor no está inicializado, prepáralo y comienza a reproducir
            startPlayback()
        } else if (mediaPlayer!!.isPlaying) {
            // Si está reproduciendo, páusalo
            mediaPlayer?.pause()
            binding.btnPlayPause.text = "Play"
            binding.tvStatus.text = "En pausa..."
            // ******* ADICIÓN: Detener actualización del Handler al pausar *******
            handler.removeCallbacks(updateSeekBarRunnable)
            // *******************************************************************

        } else {
            // Si está en pausa, reanúdalo
            mediaPlayer?.start()
            binding.btnPlayPause.text = "Pause"
            binding.tvStatus.text = "Reproduciendo..."
            binding.tvStatus.text = "Reproduciendo..."
            // ******* ADICIÓN: Reanudar actualización del Handler al reanudar *******
            handler.post(updateSeekBarRunnable)
            // ***********************************************************************

        }
    }

    /**
     * Prepara y comienza la reproducción de la canción actual.
     */
    private fun startPlayback() {
        // Limpia el reproductor anterior si existe
        mediaPlayer?.release()
        mediaPlayer = null

        if (currentSongIndex in audioList.indices) {
            val currentUri = audioList[currentSongIndex]
            try {
                // Crea un nuevo MediaPlayer con la URI de la canción
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(applicationContext, currentUri)
                    prepare()
                    start()

                    // ******* ADICIÓN: Configuración inicial del SeekBar *******
                    val duration = this.duration
                    binding.seekBar.max = duration // Establece la duración máxima
                    binding.tvDuration.text = formatTime(duration) // Muestra la duración
                    binding.seekBar.progress = 0
                    handler.post(updateSeekBarRunnable) // Inicia la actualización del SeekBar
                    // **********************************************************

                    // Listener para cuando la canción termina
                    // Listener para cuando la canción termina: llama a playNextSong()
                    setOnCompletionListener {
                        playNextSong()
                    }
                }
                binding.btnPlayPause.text = "Pause"
                binding.tvStatus.text = "Reproduciendo: ${getSongTitle(currentUri)}"
            } catch (e: Exception) {
                e.printStackTrace()
                binding.tvStatus.text = "Error al reproducir la canción."
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }


    /**
     * Función para avanzar a la siguiente canción: ADICIÓN
     */
    private fun playNextSong() {
        if (audioList.isEmpty()) {
            Toast.makeText(this, "No hay canciones en la lista.", Toast.LENGTH_SHORT).show()
            return
        }

        // Calcula el siguiente índice, usando el operador módulo (%) para volver a 0 si llega al final.
        currentSongIndex = (currentSongIndex + 1) % audioList.size

        // Inicia la reproducción de la nueva canción
        startPlayback()
    }


    /**
     * Función para retroceder a la canción anterior: ADICIÓN
     */
    private fun playPreviousSong() {
        if (audioList.isEmpty()) {
            Toast.makeText(this, "No hay canciones en la lista.", Toast.LENGTH_SHORT).show()
            return
        }

        // Calcula el índice anterior. Si es menor que 0, salta al último índice.
        currentSongIndex = if (currentSongIndex - 1 < 0) {
            audioList.size - 1 // Vuelve al final de la lista
        } else {
            currentSongIndex - 1
        }

        // Inicia la reproducción de la nueva canción
        startPlayback()
    }


    /**
     * Convierte milisegundos a formato de tiempo "m:ss".
     */
    private fun formatTime(millis: Int): String {
        return String.format(
            "%d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(millis.toLong()),
            TimeUnit.MILLISECONDS.toSeconds(millis.toLong()) -
                    TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis.toLong()))
        )
    }


    /**
     * Obtiene el nombre del archivo de la canción a partir de su URI.
     */
    private fun getSongTitle(uri: Uri): String {
        var displayName = "Título Desconocido"
        val projection = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME)

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                if (nameColumn != -1) {
                    displayName = cursor.getString(nameColumn)
                }
            }
        }
        return displayName
    }

    /**
     * Actualiza el TextView con la información de la canción actual.
     */
    private fun updateSongInfo() {
        if (currentSongIndex in audioList.indices) {
            val currentUri = audioList[currentSongIndex]
            binding.tvCurrentSong.text = getSongTitle(currentUri)
        }
    }

    /**
     * Libera el recurso del MediaPlayer cuando la Activity se destruye.
     */
    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        // ******* ADICIÓN: Detener el Handler para evitar fugas de memoria *******
        handler.removeCallbacks(updateSeekBarRunnable)
        // ***********************************************************************

    }
}