package com.stan4695.ainavigationassist.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class BluetoothCommunication(private val context: Context) {
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var isConnected = false
    private var isConnecting = false
    private var connectionThread: Thread? = null
    private var readThread: Thread? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3
    private val reconnectDelayMs = 2000L

    // On-demand reading control
    private val isReadingActive = AtomicBoolean(false) // Tracks if a read operation is currently active
    private var sensor1Read = false
    private var sensor2Read = false

    private val handler = Handler(Looper.getMainLooper())
    private var dataListener: ((Int, Int) -> Unit)? = null
    private var errorListener: ((String) -> Unit)? = null

    // Initializarea variabilelor pentru distanțele senzorilor
    private var lastSensor1Distance = 0
    private var lastSensor2Distance = 0

    companion object {
        private const val TAG = "BluetoothManager"
        private const val ESP32_DEVICE_NAME = "ESP32_AINavAssist"
        private val UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard SPP UUID
    }

    // Verfica daca Bluetooth-ul este activat
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    fun onDataUpdate(listener: (sensor1Distance: Int, sensor2Distance: Int) -> Unit) {
        this.dataListener = listener

        // Immediately provide last known values if available
        if (lastSensor1Distance > 0 || lastSensor2Distance > 0) {
            handler.post {
                listener(lastSensor1Distance, lastSensor2Distance)
            }
        }
    }

    // Functie de conectare la ESP32
    fun connectToESP32() {
        if (!isBluetoothEnabled()) {
            notifyError("Bluetooth e dezactivat")
            return
        }

        if (isConnected) {
            Log.d(TAG, "Deja conectat la ESP32")
            return
        }

        // Resetam numarul de incercari de reconectare inaintea inceperii uneia noi
        reconnectAttempts = 0

        connectionThread = thread {
            try {
                // Find the ESP32 device
                val pairedDevices = try {
                    bluetoothAdapter?.bondedDevices
                } catch (e: SecurityException) {
                    notifyError("Lipsesc permisiunile Bluetooth pentru a accesa dispozitivele asociate")
                    return@thread
                }

                val esp32Device = pairedDevices?.find { it.name == ESP32_DEVICE_NAME }

                if (esp32Device == null) {
                    Log.e(TAG, "ESP32_AINavAssist nu a fost gasit in dispozitivele asociate")
                    notifyError("ESP32 nu a fost gasit. Imperechiati dispozitivele prima data.")

                    // Foloseste datele de fallback
                    provideFallbackData()
                    return@thread
                }

                // Conectarea propriu-zisa la ESP32 prin apelarea functiei connectToDevice()
                Log.d(TAG, "ESP32 a fost gasit: ${esp32Device.name}")
                connectToDevice(esp32Device)
            } catch (e: SecurityException) {
                notifyError("Eroare de securitate: ${e.message}")
                disconnect()
                provideFallbackData()
            } catch (e: Exception) {
                notifyError("Eroare de conectare: ${e.message}")
                disconnect()
                provideFallbackData()
            }
        }
    }

    // Definim functia care se ocupa cu conectarea efectiva la dispozitivul ESP32
    private fun connectToDevice(device: BluetoothDevice) {
        synchronized(this) { // Sincronizam pentru a proteja starea conexiunii si creearea de sockets
            if (isConnecting || isConnected) {
                Log.d(TAG, "connectToDevice: Deja se conectează sau este conectat. Se anulează noua încercare.")
                return
            }
            isConnecting = true
        }

        // Asiguram ca orice socket anterior este inchis complet si nulizat prin apelarea functiei disconnect()
        if (bluetoothSocket != null) {
            Log.w(TAG, "connectToDevice: bluetoothSocket nu e null. Se apeleaza functia disconnect().")
            disconnect()
        }

        try {
            Log.d(TAG, "Crearea RFCOMM socket pentru ${device.name}")
            bluetoothSocket = device.createRfcommSocketToServiceRecord(UUID_SPP)

            Log.d(TAG, "Încercare de conectare la ${device.name}...")
            bluetoothSocket?.connect()

            // Dacă connect() nu aruncă o excepție, se consideră conectat la nivel de socket
            Log.i(TAG, "Socket Bluetooth conectat cu succes la ${device.name}")
            // Obtinem fluxul de intrare
            inputStream = bluetoothSocket?.inputStream

            synchronized(this) {
                isConnected = true
                isConnecting = false
            }
            reconnectAttempts = 0 // Resetam numarul de reincercari de conectare in momentul in care procesul de incheie cu succes


        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            notifyError("Lipsa permisiunilor Bluetooth.")
            disconnect()
        } catch (e: IOException) {
            Log.e(TAG, "IOException pentru ${device.name}: ${e.message}")
            disconnect()
            attemptReconnect(device)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception pentru ${device.name}: ${e.message}")
            notifyError("Unexpected connection error: ${e.message}")
            disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptReconnect(device: BluetoothDevice) {
        synchronized(this) {
            if (isConnecting) {
                Log.d(TAG, "attemptReconnect: Dispozitivul se afla deja in stare de conectare / reconectare.")
                return
            }
            if (reconnectAttempts >= maxReconnectAttempts) {
                Log.e(TAG, "S-a atins numarul maxim de incercari de conectare pentru ${device.name}. Nu se vor mai realiza alte incercari.")
                isConnecting = false
                isConnected = false
                provideFallbackData()
                return
            }
            reconnectAttempts++
            Log.d(TAG, "Incercare de reconectare la ${device.name} (${reconnectAttempts}/${maxReconnectAttempts})")
        }

        // Asteptam un delay inainte de a incerca reconectarea
        try {
            Thread.sleep(reconnectDelayMs)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Reconnect delay interrupted.")
            Thread.currentThread().interrupt()
            synchronized(this) { isConnecting = false }
            return
        }

        // Incercam sa restabilim conexiunea
        connectToDevice(device)
    }

    fun requestSensorData() {
        if (!isConnected || inputStream == null) {
            Log.d(TAG, "Nu se pot obtine date de la senzori. Conexiunea nu a fost stabilita cu succes.")
            provideFallbackData()
            return
        }

        if (isReadingActive.get()) {
            Log.d(TAG, "Datele de la senzori sunt deja citite. Se anuleaza noua cerere")
            return // Another read is already in progress
        }

        // Resetam flagurile de citire
        sensor1Read = false
        sensor2Read = false
        
        // Incepem citirea datelor
        startReadingData()
    }

    private fun startReadingData() {
        // Se actualizeaza contoarele de stare
        isReadingActive.set(true) // Marcheaza inceperea citirii datelor

        Log.d(TAG, "Incepe citirea datelor prin BT.")
        
        readThread = thread {
            val buffer = ByteArray(1024) // Creearea unui buffer de 1024 octeti
            var bytes: Int // Numarul de bytes cititi din fluxul de intrare
            var dataBuffer = StringBuilder() // Definirea unui buffer in care vom retine string-urile
            val startTime = System.currentTimeMillis() // Timestamp-ul de inceput al citirii
            val readTimeout = 3000L // Timp de timeout pentru citirea datelor (3 secunde)

            while (isConnected && isReadingActive.get() && !(sensor1Read && sensor2Read)) {
                try {
                    // Se verifica daca a expirat timpul de citire
                    if (System.currentTimeMillis() - startTime > readTimeout) {
                        Log.w(TAG, "A fost depasit timpul de citire de ${readTimeout}ms")
                        break
                    }
                    
                    // Se verifica daca sunt trasmise date pentru a putea fi citite
                    val available = inputStream?.available() ?: 0
                    if (available == 0) {
                        Thread.sleep(50)
                        continue
                    }
                    
                    bytes = inputStream?.read(buffer) ?: -1

                    if (bytes == -1) {
                        // Incheierea fluxului de intrare sau pierderea conexiunii
                        Log.e(TAG, "Eroare de conexiune: citirea a eșuat, socketul poate fi închis sau a expirat timpul de așteptare, citirea a returnat: -1")
                        break
                    }

                    if (bytes > 0) {
                        val receivedData = String(buffer, 0, bytes)
                        dataBuffer.append(receivedData)

                        // Procesam datele primite
                        var newlineIndex = dataBuffer.indexOf("\n")
                        while (newlineIndex != -1) {
                            val line = dataBuffer.substring(0, newlineIndex).trim()
                            if (line.isNotEmpty()) {
                                processReceivedLine(line)
                                
                                // Verificam daca am citit datele de la ambii senzori, iar in caz afirmativ, iesim din bucla
                                if (sensor1Read && sensor2Read) {
                                    Log.d(TAG, "Au fost citite datele de la ambii senzori.")
                                    notifyDataUpdate() // Notificam CameraFragment prin intermediul listener-ului onDataUpdate()
                                    break
                                }
                            }
                            dataBuffer.delete(0, newlineIndex + 1)
                            newlineIndex = dataBuffer.indexOf("\n")
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Eroare la citirea datelor: ${e.message}")
                    break
                } catch (e: SecurityException) {
                    notifyError("Permisiuni lipsa in timpul citirii datelor")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Eroare neasteptata la citirea datelor: ${e.message}")
                    break
                }
            }

            // S-a incheiat citirea datelor, se reseteaza contorul de stare
            isReadingActive.set(false)
            
            // Daca nu s-au citit datele de la ambii senzori, folosim date de fallback
            if (!(sensor1Read && sensor2Read)) {
                Log.d(TAG, "Nu s-au citit datele de la ambii senzori. Folosim datele de fallback.")
                provideFallbackData()
            }
        }
    }

    // Procesam datele primite linie cu linie
    private fun processReceivedLine(line: String) {
        Log.d(TAG, "Linia primita: $line")

        try {
            // Datele vor fi de forma "S1:XX.XX"
            if (line.startsWith("S1:")) {
                val valueStr = line.substringAfter("S1:")
                val floatValue = valueStr.toFloatOrNull()
                Log.d(TAG, "Valoarea S1 ca float: $floatValue")
                if (floatValue != null) {
                    val sensor1 = floatValue.toInt()
                    if (sensor1 > 0) {
                        lastSensor1Distance = sensor1
                        Log.d(TAG, "Valoarea senzorului 1 (stanga) a fost actualizata la $sensor1 cm")
                        sensor1Read = true
                    }
                }
            } else if (line.startsWith("S2:")) {
                val valueStr = line.substringAfter("S2:")
                val floatValue = valueStr.toFloatOrNull()
                Log.d(TAG, "S2 parsed value: $floatValue")
                if (floatValue != null) {
                    val sensor2 = floatValue.toInt()
                    if (sensor2 > 0) {
                        lastSensor2Distance = sensor2
                        Log.d(TAG, "Valoarea senzorului 2 (dreapta) a fost actualizata la $sensor2 cm")
                        sensor2Read = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing data line: ${e.message}")
        }
    }


    private fun notifyDataUpdate() {
        handler.post {
            dataListener?.invoke(lastSensor1Distance, lastSensor2Distance)
        }
    }

    // Definim o functie care sa simuleze distantele atunci cand conexiunea Bluetooth nu are succes
    private fun provideFallbackData() {
        handler.post {
            val simulatedDistance1 = 1
            val simulatedDistance2 = 2

            dataListener?.invoke(simulatedDistance1.toInt(), simulatedDistance2.toInt())
        }
    }

    fun disconnect() {
        isReadingActive.set(false) // Stop any active reading
        
        connectionThread?.interrupt()
        readThread?.interrupt()
        connectionThread = null
        readThread = null

        // Incheiem stream-ul de intrare
        try {
            Log.d(TAG, "Closing inputStream...")
            inputStream?.close()
            Log.d(TAG, "InputStream closed.")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing input stream: ${e.message}")
        } finally {
            inputStream = null
        }

        // Incheiem socket-ul
        try {
            Log.d(TAG, "Closing bluetoothSocket...")
            bluetoothSocket?.close()
            Log.d(TAG, "BluetoothSocket closed.")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing bluetooth socket: ${e.message}")
        } finally {
            bluetoothSocket = null
        }
        isConnected = false
        isConnecting = false
        reconnectAttempts = 0

        Log.d(TAG, "disconnect() finished. isConnected: $isConnected, isConnecting: $isConnecting")
    }

    private fun notifyError(message: String) {
        Log.e(TAG, message)
        handler.post { errorListener?.invoke(message) }
    }
}
