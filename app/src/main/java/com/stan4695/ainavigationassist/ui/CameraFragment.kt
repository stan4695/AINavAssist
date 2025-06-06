package com.stan4695.ainavigationassist.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.stan4695.ainavigationassist.BoundingBox
import com.stan4695.ainavigationassist.ModelConstants.LABELS_PATH
import com.stan4695.ainavigationassist.ModelConstants.MODEL_PATH
import com.stan4695.ainavigationassist.Detector
import com.stan4695.ainavigationassist.R
import com.stan4695.ainavigationassist.bluetooth.BluetoothCommunication
import com.stan4695.ainavigationassist.databinding.FragmentCameraBinding
import com.stan4695.ainavigationassist.haptic.HapticFeedbackManager
import com.stan4695.ainavigationassist.settings.SettingsManager
import com.stan4695.ainavigationassist.tts.TTSManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.graphics.createBitmap

@Suppress("DEPRECATION")
class CameraFragment : Fragment(), Detector.DetectorListener {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!
    private val isFrontCamera = false
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    
    // Variabila de control a functiei Hardware Acceleration 
    private var currentGPUState: Boolean = false
    
    // Variabile de management al starilor
    private var isCameraInitializing = false
    private var isBluetoothConnecting = false
    private var cameraInitialized = false
    private val handler = Handler(Looper.getMainLooper())

    // Initializam cameraExecutor
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Elementele de asistenta pentru persoanele cu deficiente de vedere (TTS + Haptic Feedback)
    private lateinit var ttsManager: TTSManager
    private lateinit var bluetoothCommunication: BluetoothCommunication
    private lateinit var hapticManager: HapticFeedbackManager

    // Initializam variabilele de control + stocare a datelor de la senzori
    private var sensor1Distance: Int = 0
    private var sensor2Distance: Int = 0
    private var needSensorData = false
    // Variabile pentru a stoca ultimul obstacol detectat si pozitia sa
    private var lastDetectedObstacle: BoundingBox? = null
    private var lastObstaclePosition: ObstaclePosition? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initializam detectorul respectand setarea legata de HardwareAcceleration
        cameraExecutor.execute {
            val gpuSetting = SettingsManager.isGpuAccelerationEnabled(requireContext())
            currentGPUState = gpuSetting
            detector = Detector(requireContext(), MODEL_PATH, LABELS_PATH, this, gpuSetting) {
                toast(it)
            }
        }

        // Verificam daca avem permisiunile necesare si initializam camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
        // Permite trecerea din ecranul principal (CameraFragment) in SettingsFragment la apasarea butonului Settings
        binding.apply {
            // Asociaza butonul Bluetooth cu actiunea corespunzatoare definita in nav_graph.xml
            btnSettings.setOnClickListener {
                findNavController().navigate(R.id.action_cameraFragment_to_settingsFragment)
            }
        }

        // Initializarea componentelor de accesibilitate (TTS + Haptic Feedback)
        initializeAccessibilityComponents()
        
    }

    private fun initializeAccessibilityComponents() {
        // Initializare TTS Manager
        ttsManager = TTSManager(requireContext())

        // Initializare Bluetooth Manager
        bluetoothCommunication = BluetoothCommunication(requireContext())

        // Initializare Haptic Feedback Manager
        hapticManager = HapticFeedbackManager(requireContext())

        // Configuram Bluetooth listener, care permit actualizarea datelor de la senzori
        bluetoothCommunication.onDataUpdate { s1Distance, s2Distance ->
            sensor1Distance = s1Distance
            sensor2Distance = s2Distance
            // Procesarea datelor de la senzorii ultrasonici si oferirea feedback-ului haptic pe baza acestora
            hapticManager.processSensorData(sensor1Distance, sensor2Distance)
            
            // Marcam faptul ca am primit datele de la senzori
            needSensorData = false
            // Verificam daca avem un obstacol in asteptare pentru anunt
            lastDetectedObstacle?.let { obstacle ->
                lastObstaclePosition?.let { position ->
                    val distanceToUse = when (position) {
                        ObstaclePosition.LEFT -> sensor1Distance
                        ObstaclePosition.RIGHT -> sensor2Distance
                        ObstaclePosition.CENTER -> minOf(sensor1Distance, sensor2Distance).takeIf { it > 0 } ?: maxOf(sensor1Distance, sensor2Distance)
                    }
                    generateAnnouncementMessage(obstacle, position, distanceToUse)                }
            }
        }
    }

    private fun initializeBluetoothConnection() {
        // Verificam daca Camera este initializata
        // Daca camera este inca in proces de initializare, amanam initializarea conexiunii Bluetooth cu o secunda
        // Dupa trecerea intervalului de asteptare, verificam iar starea camerei
        // Daca inca nu a terminat initializarea, repetam procesul prin reapelarea functiei initializeBluetoothConnection()
        if (isCameraInitializing) {
            Log.d(TAG, "Camera se initializeaza. Initializarea Bluetooth este amanata.")
            lifecycleScope.launch {
                delay(1000) // Introducerea unui delay
                if (!isCameraInitializing) {
                    initializeBluetoothConnection()
                }
            }
            return
        }
        
        // Contorizam initierea procesului de stabilire a conexiunii Bluetooth
        isBluetoothConnecting = true
        
        // Verificam daca Bluetooth este activat
        val bluetoothCommunication =
            requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothCommunication.adapter

        if (bluetoothAdapter == null) {
            // Dispozitivul nu suporta Bluetooth
            toast("Dispozitivul dvs. nu dispunde de Bluetooth")
            isBluetoothConnecting = false
            return
        } else {
            if (!bluetoothAdapter.isEnabled) {
                // Dispozitivul suporta Bluetooth insa acesta este dezactivat
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                isBluetoothConnecting = false
            } else {
                // Dispozitivul suporta Bluetooth si este deja activat
                this.bluetoothCommunication.connectToESP32()
                isBluetoothConnecting = false
            }
        }
    }

    private fun startCamera() {
        // Verificam daca Camera se afla deja in procesul de initializare
        if (isCameraInitializing) {
            Log.d(TAG, "Camera are deja un proces de initializare activ.")
            return
        }
        
        // Contorizam initializarea camerei, pentru a preveni posibilele conflicte
        isCameraInitializing = true
        cameraInitialized = false
        
        // Ne asiguram ca orice resurse anterior asignate camerei sunt eliberate
        cameraProvider?.unbindAll()
        imageAnalyzer = null
        preview = null
        
        // Initializam camera
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Initializarea camerei a esuat.")

        // Orientarea este intotdeauna portret
        val rotation = Surface.ROTATION_0
        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            // Acest cod reprezinta implementarea functiei analyze() din ImageAnalysis.Analyzer si este executat pentru fiecare cadru nou
            try {
                val bitmapBuffer = createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
                imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
                
                val matrix = Matrix().apply {
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                    if (isFrontCamera) {
                        postScale(
                            -1f,
                            1f,
                            imageProxy.width.toFloat(),
                            imageProxy.height.toFloat()
                        )
                    }
                }

                val rotatedBitmap = Bitmap.createBitmap(
                    bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                    matrix, true
                )

                detector?.detect(rotatedBitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Eroare la procesarea imaginii: ${e.message}", e)
                // In cazul aparitiei unei erori de tip timeout, restartam camera
                if (e.message?.contains("timeout") == true) {
                    handler.post {
                        Log.w(TAG, "A fost detectat un timeout pentru ImageReader. Reinitializam camera.")
                        cameraProvider.unbindAll()
                        startCamera()
                    }
                }
            } finally {
                imageProxy.close()
            }
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            // Asociem preview-ul elementului din UI numit viewFinder
            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
            
            // Contorizam finalizarea procesului de initializare a camerei
            isCameraInitializing = false
            cameraInitialized = true
            
            Log.d(TAG, "Camera a fost initializata cu success")
        } catch (exc: Exception) {
            Log.e(TAG, "Initializarea camerei a eșuat: ${exc.message}", exc)
            isCameraInitializing = false
            cameraInitialized = false
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val bluetoothConnectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
        val bluetoothScanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] ?: false

        if (cameraGranted) {
            startCamera()
        }
        if (bluetoothConnectGranted && bluetoothScanGranted) {
            initializeBluetoothConnection()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            toast("Pentru utilizarea datelor de la senzorii ultrasonici este nevoie de permisiunea Bluetooth.")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()

        // Inchidem detectorul
        try {
            detector?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Eroare la inchiderea detectorului: ${e.message}", e)
        }

        // Oprim executorul camerei
        try {
            cameraExecutor.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Eroare la oprirea executorului camerei: ${e.message}", e)
        }

        // Incheiem conexiunea Bluetooth si oprim serviciul TTS
        try {
            if (::ttsManager.isInitialized) {
                ttsManager.shutdown()
            }

            if (::bluetoothCommunication.isInitialized) {
                bluetoothCommunication.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eroare la incheierea conexiunii Bluetooth si a TTS: ${e.message}", e)
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Resetam variabilele de control ale camerei si a Bluetooth-ului
        isCameraInitializing = false
        isBluetoothConnecting = false
        cameraInitialized = false
        
        // Verificam daca avem permisiunile necesare si pornim camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }

        // Verificam daca setarea Hardware Acceleration a fost schimbata
        val newGPUState = SettingsManager.isGpuAccelerationEnabled(requireContext())
        if (detector != null && newGPUState != currentGPUState) {
            Log.d(TAG, "Setarea Hardware Acceleration a fost schimbata. Reinitializam detectorul.")
            cameraExecutor.submit {
                try {
                    // Incercam reinitializarea detectorului
                    detector?.restart(newGPUState)
                    toast("Setarea Hardware Acceleration a fost schimbata cu succes.")
                } catch (e: Exception) {
                    Log.e(TAG, "A avut loc o eroarer la reinitializarea detectorului: ${e.message}", e)
                    toast("A avut loc o eroare la reinitializarea detectorului.")
                }
            }
        }
        currentGPUState = newGPUState // Actualizam variabila cu noua valoare a setarii Hardware Acceleration

        // Adugam un delay inaintea restabilirii conexiunii Bluetooth pentru a evita conflictele cu procesul de initializare al camerei
        lifecycleScope.launch {
            delay(500)
            // Verificam daca Bluetooth este activat si restabilim conexiunea cu ESP32
            if (::bluetoothCommunication.isInitialized && bluetoothCommunication.isBluetoothEnabled()) {
                bluetoothCommunication.connectToESP32()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Disconnect Bluetooth
        bluetoothCommunication.disconnect()
        
        // Reset state variables
        isCameraInitializing = false
        isBluetoothConnecting = false
        cameraInitialized = false
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_ENABLE_BT = 11
        private val REQUIRED_PERMISSIONS_LIST = mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        private val REQUIRED_PERMISSIONS = REQUIRED_PERMISSIONS_LIST.toTypedArray()
    }

    override fun onEmptyDetect() {
        requireActivity().runOnUiThread {
            if (_binding != null) {
                binding.overlay.clear()
            }

            // Stergerea istoricului pentru obiecte neobservate in acest frame care sunt considerate invechite
            detectionHistory.entries.removeIf { it.value.isStale() }

            // Ofera feedback utilizatorului referitor la lipsa de detectie cauzata de un prag prea ridicat
            ttsManager.announceNoDetections()
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        requireActivity().runOnUiThread {
            if (_binding != null) {
                // Filtram casetele de delimitare in functie de pragul de sensibilitate

                binding.inferenceTime.text = getString(R.string.inference_time, inferenceTime)
                binding.overlay.apply {
                    setResults(boundingBoxes) // Folosim casetele filtrate
                    invalidate()
                }

                getHighestConfidenceBB(boundingBoxes)
            }
        }
    }

    // Urmarirea obiectului detectat
    private val REQUIRED_CONSECUTIVE_FRAMES = 10
    private val detectionHistory = mutableMapOf<String, DetectionTracker>()

    private inner class DetectionTracker {
        var consecutiveFrames = 0
        var lastDetectionTime = 0L

        fun update() {
            consecutiveFrames++
            lastDetectionTime = System.currentTimeMillis()
        }

        fun reset() {
            consecutiveFrames = 0
        }

        fun isStale(): Boolean {
            return System.currentTimeMillis() - lastDetectionTime > 500 // 0.5 second timeout
        }
    }

    private enum class ObstaclePosition {
        LEFT, RIGHT, CENTER
    }

    private fun determineObstaclePosition(box: BoundingBox): ObstaclePosition {
        val boxCenterX = (box.x1 + box.x2) / 2

        return when {
            boxCenterX < 0.4 -> ObstaclePosition.LEFT
            boxCenterX > 0.6 -> ObstaclePosition.RIGHT
            else -> ObstaclePosition.CENTER
        }
    }

    private fun generateAnnouncementMessage(box: BoundingBox, position: ObstaclePosition, distance: Int) {
        if (!::ttsManager.isInitialized)
            return

        val positionText = when (position) {
            ObstaclePosition.LEFT -> "on your left"
            ObstaclePosition.RIGHT -> "on your right"
            ObstaclePosition.CENTER -> "ahead"
        }

        val distanceText = if (distance > 0 && distance < 300) {
            "$distance centimeters"
        } else {
            "unknown distance"
        }

        val announcement = "${box.clsName} detected $positionText at $distanceText"
        ttsManager.announceDetection(announcement)
        Log.d(TAG, "TTS Announcement: $announcement")
    }

    // Obtinerea obstacolului cu nivelul de confidenta cel mai mare si a distantei la care acesta se afla
    private fun getHighestConfidenceBB(boundingBoxes: List<BoundingBox>) {
        // Actualizarea istoricului pentru toate obstacolele detectate
        val detectedClasses = mutableSetOf<String>()

        // Eliminam duplicatele si pastram doar casetele cu confidenta cea mai mare pentru fiecare clsName
        val distinctBoundingBoxesByClsName = boundingBoxes
            .groupBy { it.clsName }
            .mapNotNull { (_, boxesInGroup) ->
                boxesInGroup.maxByOrNull { it.cnf }
            }

        for (box in distinctBoundingBoxesByClsName) {
            detectedClasses.add(box.clsName)

            val tracker = detectionHistory.getOrPut(box.clsName) { DetectionTracker() }
            tracker.update()

            // Verifica daca obstacolul a fost detectat in mai multe cadre succesive pentru a reduce numarul false pozitivelor
            if (tracker.consecutiveFrames >= REQUIRED_CONSECUTIVE_FRAMES) {
                // Anuntam doar acele obstacole care apar in cel putin 10 (REQUIRED_CONSECUTIVE_FRAMES) cadre consecutive
                box.let {
                    // Determinam pozitia obstacolului (stanga, dreapta sau in fata)
                    val position = determineObstaclePosition(it)
                    // Salvam obstacolul si pozitia pentru generarea anuntului din callback-ul onDataUpdate()
                    lastDetectedObstacle = it
                    lastObstaclePosition = position
                    // Cerem datele de la senzori doar atunci cand se detecteaza un obstacol
                    if (!needSensorData && ::bluetoothCommunication.isInitialized) {
                        Log.d(TAG, "Se cer datele de la senzori pentru a avertiza utilizatorul.")
                        needSensorData = true
                        bluetoothCommunication.requestSensorData()
                    }
                }
            }
        }

        // Resetam istoricul pentru clasele care nu au fost detectate in frame-ul curent
        detectionHistory.entries
            .filter { !detectedClasses.contains(it.key) }
            .forEach { it.value.reset() }

        // Stergem istoricul mai vechi de 0,5 secunde
        detectionHistory.entries.removeIf { it.value.isStale() }
    }

    private fun toast(message: String) {
        // Ne asiguram ca toast ruleaza pe thread-ul principal al UI pentru a evita aparitia crash-urilot
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        } else {
            handler.post {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}