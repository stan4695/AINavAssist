package com.stan4695.ainavigationassist

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.stan4695.ainavigationassist.MetaData.extractNamesFromLabelFile
import com.stan4695.ainavigationassist.settings.SettingsManager
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String?,
    private val detectorListener: DetectorListener,
    private var isGpuEnabled: Boolean = false,
    private val showMessage: (String) -> Unit
) {
    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    init {
        val interpreterOptions = Interpreter.Options()

        if (isGpuEnabled) { // Daca setarea hardware acceleration este activata
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                interpreterOptions.addDelegate(GpuDelegate(delegateOptions))
                Log.d("Detector", "Hardware acceleration enabled")
            } else {
                showMessage("Acest dispozitiv nu suporta hardware acceleration.")
                Log.w("Detector", "Acest dispozitiv nu suporta hardware acceleration. Se va folosi CPU.")
            }
        } else {
            interpreterOptions.setNumThreads(4) // Implicit 4 fire de executie pentru CPU
            Log.d("Detector", "Hardware acceleration nu este activat in setari. Se va folosi CPU.")
        }

        val modelFile = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(modelFile, interpreterOptions)

        // Extrage etichetele din fisierul de etichete
        if (labelPath == null) {
            showMessage("Nu s-au putut extrage etichetele. Calea catre fisierul de etichete este goala.")
            labels.addAll(MetaData.TEMP_CLASSES)
        } else {
            labels.addAll(extractNamesFromLabelFile(context, labelPath))
        }

        labels.forEach(::println)

        val inputShape = interpreter.getInputTensor(0)?.shape() // Extrage dimensiunile tensorului de intrare
        val outputShape = interpreter.getOutputTensor(0)?.shape() // Extrage dimensiunile tensorului de iesire

        // Conform Netron, formatul unui tensor de intrare este float32[1, 640, 640, 3], deci formatul (Batch, Width, Height, Channels)
        if (inputShape != null) {
            tensorWidth = inputShape[1] // extragem Width din tensorul de intrare
            tensorHeight = inputShape[2] // extragem Height din tensorul de intrare
        }

        // Formatul unui tensor de iesire este float[1, 300, 6]
        if (outputShape != null) {
            numElements = outputShape[1] // extragem numarul de elemente din tensorul de iesire
            numChannel = outputShape[2] // extragem numarul de canale din tensorul de iesire
        }
    }
    // Reporneste interpretorul cu setari noi pentru GPU
    fun restart(enableGpu: Boolean) {
        interpreter.close()

        val interpreterOptions = if (enableGpu) {
            val compatList = CompatibilityList()
            Interpreter.Options().apply{
                if(compatList.isDelegateSupportedOnThisDevice){
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    this.addDelegate(GpuDelegate(delegateOptions))
                } else {
                    this.setNumThreads(4)
                }
            }
        } else {
            Interpreter.Options().apply{
                this.setNumThreads(4)
            }
        }

        val modelFile = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(modelFile, interpreterOptions)
        this.isGpuEnabled = enableGpu
    }
    // Inchide interpretorul TensorFlow Lite
    fun close() {
        interpreter.close()
    }

    // Efectueaza detectarea obiectelor pe un cadru dat (Bitmap)
    fun detect(frame: Bitmap) {
        if (tensorWidth == 0
            || tensorHeight == 0
            || numChannel == 0
            || numElements == 0) return

        var inferenceTime = SystemClock.uptimeMillis()

        // Redimensioneaza bitmap-ul la dimensiunile asteptate de tensor
        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)

        val tensorImage = TensorImage(INPUT_IMAGE_TYPE)
        tensorImage.load(resizedBitmap) // Incarca imaginea in TensorImage
        val processedImage = imageProcessor.process(tensorImage) // Preproceseaza imaginea
        val imageBuffer = processedImage.buffer // Imaginea de intrare preprocesata (normalizare si cast la FLOAT32)

        // Creeaza un buffer de iesire pentru rezultatele detectarii
        val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
        interpreter.run(imageBuffer, output.buffer) // Efectueaza inferenta

        // Extrage cele mai bune casete de delimitare din rezultatele detectarii
        val bestBoxes = bestBox(output.floatArray)

        // Calculeaza timpul de inferenta
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        if (bestBoxes.isEmpty()) {
            detectorListener.onEmptyDetect() // Daca lista de casete de delimitare este goala dupa apelarea bestBoxes(), se notifica clasa CameraFragment
            return
        }

        detectorListener.onDetect(bestBoxes, inferenceTime)
    }

    // Proceseaza rezultatele brute ale detectiei pentru a obtine casetele de delimitare cu un nivel de confidenta ridicat
    private fun bestBox(array: FloatArray) : List<BoundingBox> {
        val boundingBoxes = mutableListOf<BoundingBox>()
        for (resultIndex in 0 until numElements) {
            val confidence = array[resultIndex * numChannel + 4]
            val sensitivityThreshold = SettingsManager.getDetectionSensitivity(context)
            if (confidence > sensitivityThreshold) {
                val xAxis1 = array[resultIndex * numChannel]
                val yAxis1 = array[resultIndex * numChannel + 1]
                val xAxis2 = array[resultIndex * numChannel + 2]
                val yAxis2 = array[resultIndex * numChannel + 3]
                val classIndex = array[resultIndex * numChannel + 5].toInt()
                val className = labels[classIndex]
                boundingBoxes.add(
                    BoundingBox(
                        x1 = xAxis1, y1 = yAxis1, x2 = xAxis2, y2 = yAxis2,
                        cnf = confidence, cls = classIndex, clsName = className
                    )
                )
            }
        }

        return boundingBoxes
    }

    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
    }
}