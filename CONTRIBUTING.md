# Contributing to Aura (Ping)

Cross-platform file sharing with BLE discovery and NearbyExchange.

## Quick Start

```bash
# Clone and setup
git clone https://github.com/Yash-Awasthi/Ping.git
cd Aura

# Open in Android Studio
# File → Open → Select Aura directory

# Sync Gradle
# File → Sync Project with Gradle Files

# Run tests
./gradlew test

# Build APK
./gradlew assembleDebug
```

## Tech Stack

| Component | Technology | Version |
|-----------|------------|---------|
| Language | Kotlin | 1.9+ |
| Platform | Android | API 24+ (Android 7.0) |
| DI | Hilt | 2.48+ |
| Async | Coroutines | 1.7+ |
| UI | Jetpack Compose | 1.5+ |
| BLE | Android BLE | API 21+ |
| Nearby | Google Nearby | 1.0+ |
| Testing | JUnit | 5.0+ |

## Project Structure

```
app/src/main/java/com/ping/app/
├── di/                    # Hilt dependency injection
│   └── AppModule.kt
├── data/                  # Data layer
│   ├── SessionRepository.kt
│   └── QRPairingManager.kt
├── viewmodel/             # MVVM ViewModels
│   └── SessionViewModel.kt
├── ui/                    # UI components
│   ├── MainActivity.kt
│   ├── QRScannerScreen.kt
│   ├── QRDisplayScreen.kt
│   └── ConnectionStatusIndicator.kt
├── protocol/              # Transfer protocols
│   ├── LocalSendProtocol.kt
│   └── FileChunker.kt
└── security/              # Encryption
    └── EndToEndEncryption.kt
```

## Development Guidelines

### Service Development

Services use **dependency injection** with Hilt:

```kotlin
// Good: Injected service
@Singleton
class TransferService @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val encryption: EndToEndEncryption
) {
    suspend fun transferFile(file: File, peerId: String): Result<Unit> {
        val session = sessionRepository.getSession(peerId)
            ?: return Result.failure(Exception("No session"))
        
        val chunks = encryption.encryptFile(
            file.readBytes(),
            session.sharedSecret
        )
        
        return sendChunks(chunks, peerId)
    }
}

// Bad: Service with static methods
class TransferService {
    companion object {
        fun transferFile(file: File, peerId: String) { ... }
    }
}
```

### ViewModel Development

```kotlin
// Good: ViewModel with StateFlow
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val repository: SessionRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()
    
    fun connectToDevice(device: BluetoothDevice) {
        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true) }
            
            repository.connect(device)
                .onSuccess { session ->
                    _uiState.update { 
                        it.copy(
                            isConnected = true,
                            session = session,
                            isConnecting = false
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(
                            error = error.message,
                            isConnecting = false
                        )
                    }
                }
        }
    }
}

// Bad: ViewModel with LiveData
class SessionViewModel : ViewModel() {
    val isConnected = MutableLiveData<Boolean>()
    val error = MutableLiveData<String>()
}
```

### BLE Operations

```kotlin
// Use coroutines for BLE operations
suspend fun scanForDevices(): Flow<BluetoothDevice> = callbackFlow {
    val scanner = BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner
    
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            trySend(result.device)
        }
    }
    
    scanner.startScan(callback)
    
    awaitClose {
        scanner.stopScan(callback)
    }
}
```

### Security

```kotlin
// Always use SecureRandom for keys
val keyGenerator = KeyGenerator.getInstance("AES")
keyGenerator.init(256, SecureRandom())
val secretKey = keyGenerator.generateKey()

// Use X25519 for key exchange
val keyPairGenerator = KeyPairGenerator.getInstance("X25519")
val keyPair = keyPairGenerator.generateKeyPair()

// Constant-time comparison
fun secureCompare(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var result = 0
    for (i in a.indices) {
        result = result or (a[i].toInt() xor b[i].toInt())
    }
    return result == 0
}
```

### Testing

```kotlin
// Unit tests
class TransferServiceTest {
    @Test
    fun `encrypt then decrypt preserves data`() {
        val encryption = EndToEndEncryption()
        val keyPair = encryption.generateKeyPair()
        val sharedSecret = encryption.deriveSharedSecret(keyPair.privateKey)
        
        val original = "Hello, World!".toByteArray()
        val encrypted = encryption.encrypt(original, sharedSecret)
        val decrypted = encryption.decrypt(encrypted, sharedSecret)
        
        assertArrayEquals(original, decrypted)
    }
}

// Run tests
./gradlew test

// Run specific test class
./gradlew test --tests "com.ping.app.security.EndToEndEncryptionTest"
```

## Pull Request Checklist

- [ ] Tests pass (`./gradlew test`)
- [ ] No memory leaks (check with LeakCanary)
- [ ] BLE operations in background thread
- [ ] UI updates on main thread
- [ ] Proguard rules updated if needed
- [ ] README updated if new feature

## Commit Messages

```
feat: add QR code pairing screen
fix: handle BLE permission denial gracefully
security: add end-to-end encryption layer
ui: improve connection status indicator
test: add encryption unit tests
```
