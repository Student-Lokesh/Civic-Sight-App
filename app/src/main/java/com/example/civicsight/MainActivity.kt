package com.example.civicsight

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var selectedBitmap: Bitmap? = null
    private lateinit var imageView: ImageView
    private lateinit var tvResult: TextView
    private lateinit var progressBar: ProgressBar

    // Live Location & Dynamic Email Variables
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var liveAddress: String = "📍 Location: Fetching..."
    private var dynamicTargetEmail: String = "complaints@mpurban.gov.in"

    // API Key
    private val apiKey = "YOUR_API_KEY_HERE"

    // Location Permission Launcher
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            checkGpsAndFetchLocation() // Updated: Permission milne k baad GPS check karega
        } else {
            liveAddress = "📍 Location: Permission Denied by User"
        }
    }

    // Photo pick launcher
    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val imageUri = result.data?.data
            imageUri?.let {
                selectedBitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, it)
                imageView.setImageBitmap(selectedBitmap)

                imageView.clearColorFilter()
                imageView.imageTintList = null

                tvResult.text = "Photo Selected. Click 'Analyze Issue'.\nफोटो चुन ली गई है।"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        tvResult = findViewById(R.id.tvResult)
        progressBar = findViewById(R.id.progressBar)

        val btnSelectImage = findViewById<Button>(R.id.btnSelectImage)
        val btnAnalyze = findViewById<Button>(R.id.btnAnalyze)
        val btnSendEmail = findViewById<Button>(R.id.btnSendEmail)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        requestLocationPermission()

        btnSelectImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImage.launch(intent)
        }

        btnAnalyze.setOnClickListener {
            if (selectedBitmap == null) {
                Toast.makeText(this, "Please select an image first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Analyze press karne par ek baar wapas location check kar lete hain
            if (liveAddress.contains("Fetching") || liveAddress.contains("OFF")) {
                checkGpsAndFetchLocation()
            }

            val languages = arrayOf("English", "Hindi")
            val builder = android.app.AlertDialog.Builder(this)
            builder.setTitle("Select Complaint Language / भाषा चुनें")

            builder.setItems(languages) { _, which ->

                progressBar.visibility = View.VISIBLE

                if (which == 0) {
                    tvResult.text = "AI is analyzing the image... Please wait..."
                } else {
                    tvResult.text = "AI असली फोटो का विश्लेषण कर रहा है... कृपया प्रतीक्षा करें..."
                }

                val currentTime = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
                val timeString = "🕒 Time: $currentTime"

                // Updated Strict Prompt logic
                val promptText = if (which == 0) {
                    "Analyze this image of a pothole. Is it a Deep Pothole (High Priority) or Small Pothole (Low Priority)? Write a formal 2-line complaint for the municipality in English. Format exactly like this:\n🚨 Hazard: [Deep/Small] Pothole\n🔴 Priority: [High/Low]\nComplaint: [Your 2-line complaint here]"
                } else {
                    "Analyze this image of a pothole. You MUST write the hazard type, priority, and a formal 2-line complaint for the municipality STRICTLY in pure Hindi language (Devanagari script). Do NOT use English for the output. Format exactly like this:\n🚨 खतरा: [गहरा या छोटा] गड्ढा\n🔴 प्राथमिकता: [उच्च या निम्न]\nशिकायत: [यहाँ हिंदी में 2 लाइन की औपचारिक शिकायत लिखें]"
                }

                val closingMessage = if (which == 0) "Thank you!" else "धन्यवाद!"

                lifecycleScope.launch(Dispatchers.IO) {
                    var attempt = 0
                    val maxRetries = 3
                    var success = false

                    while (attempt < maxRetries && !success) {
                        try {
                            attempt++

                            val baos = java.io.ByteArrayOutputStream()
                            selectedBitmap!!.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, baos)
                            val base64Image = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)

                            val jsonBody = org.json.JSONObject()
                            val contentsArray = org.json.JSONArray()
                            val partsArray = org.json.JSONArray()

                            val textPart = org.json.JSONObject().apply { put("text", promptText) }
                            partsArray.put(textPart)

                            val inlineData = org.json.JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            }
                            val imagePart = org.json.JSONObject().apply { put("inline_data", inlineData) }
                            partsArray.put(imagePart)

                            val contentObj = org.json.JSONObject().apply { put("parts", partsArray) }
                            contentsArray.put(contentObj)
                            jsonBody.put("contents", contentsArray)

                            val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=$apiKey")
                            val connection = url.openConnection() as java.net.HttpURLConnection
                            connection.requestMethod = "POST"
                            connection.setRequestProperty("Content-Type", "application/json")
                            connection.doOutput = true
                            connection.connectTimeout = 15000
                            connection.readTimeout = 15000

                            connection.outputStream.use { os ->
                                val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
                                os.write(input, 0, input.size)
                            }

                            val responseCode = connection.responseCode
                            if (responseCode == 200) {
                                success = true
                                val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                                val jsonResponse = org.json.JSONObject(responseString)
                                val textResult = jsonResponse.getJSONArray("candidates")
                                    .getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .getString("text")

                                withContext(Dispatchers.Main) {
                                    progressBar.visibility = View.GONE
                                    tvResult.text = "$liveAddress\n$timeString\n\n$textResult\n\n$closingMessage"
                                }
                            } else if (responseCode == 503) {
                                if (attempt < maxRetries) {
                                    withContext(Dispatchers.Main) {
                                        tvResult.text = "Server busy, retrying... (Attempt $attempt/$maxRetries)"
                                    }
                                    delay(2500)
                                } else {
                                    withContext(Dispatchers.Main) {
                                        progressBar.visibility = View.GONE
                                        tvResult.text = "The AI server is experiencing high traffic. Please try again in a few moments."
                                    }
                                }
                            } else {
                                success = true
                                val errorString = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown Error"
                                withContext(Dispatchers.Main) {
                                    progressBar.visibility = View.GONE
                                    tvResult.text = "System Error ($responseCode):\n$errorString"
                                }
                            }
                        } catch (e: Exception) {
                            if (attempt >= maxRetries) {
                                withContext(Dispatchers.Main) {
                                    progressBar.visibility = View.GONE
                                    tvResult.text = "Connection Timeout. The internet is slow or the server is unresponsive. Please check your network and try again."
                                }
                            } else {
                                delay(2000)
                            }
                        }
                    }
                }
            }
            builder.show()
        }

        btnSendEmail.setOnClickListener {
            val complaintText = tvResult.text.toString()
            if (complaintText.contains("AI") || complaintText.contains("Error") || complaintText.contains("Timeout") || complaintText.contains("busy")) {
                Toast.makeText(this, "Please complete the AI analysis first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(dynamicTargetEmail))
                putExtra(Intent.EXTRA_SUBJECT, "Urgent: Pothole Hazard Report")
                putExtra(Intent.EXTRA_TEXT, complaintText)
            }
            startActivity(emailIntent)
        }
    }

    // ---------------- LIVE LOCATION & DYNAMIC EMAIL FUNCTIONS ----------------
    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            checkGpsAndFetchLocation()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // NEW FUNCTION: Checks if physical GPS is ON. If not, opens settings.
    private fun checkGpsAndFetchLocation() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled) {
            Toast.makeText(this, "Please turn on your Location (GPS) first.", Toast.LENGTH_LONG).show()
            // Sends user directly to the phone's Location Settings
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
            liveAddress = "📍 Location: GPS is currently OFF."
        } else {
            fetchLiveLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLiveLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                try {
                    val geocoder = Geocoder(this, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        liveAddress = "📍 Location: ${address.getAddressLine(0)}"

                        val city = address.locality ?: address.subAdminArea ?: ""
                        dynamicTargetEmail = assignMunicipalEmail(city)

                    } else {
                        liveAddress = "📍 Location: Lat ${location.latitude}, Lng ${location.longitude}"
                    }
                } catch (e: Exception) {
                    liveAddress = "📍 Location: Lat ${location.latitude}, Lng ${location.longitude}"
                }
            } else {
                liveAddress = "📍 Location: Searching for GPS signal..."
            }
        }
    }

    // Smart Routing Logic based on Live City
    private fun assignMunicipalEmail(city: String): String {
        val cityName = city.lowercase(Locale.getDefault())
        return when {
            cityName.contains("indore") -> "nn.indore@mpurban.gov.in"
            cityName.contains("burhanpur") -> "nn.burhanpur@mpurban.gov.in"
            cityName.contains("bhopal") -> "nn.bhopal@mpurban.gov.in"
            cityName.contains("gwalior") -> "nn.gwalior@mpurban.gov.in"
            cityName.contains("jabalpur") -> "nn.jabalpur@mpurban.gov.in"
            cityName.contains("ujjain") -> "nn.ujjain@mpurban.gov.in"
            else -> "complaints@mpurban.gov.in"
        }
    }
}