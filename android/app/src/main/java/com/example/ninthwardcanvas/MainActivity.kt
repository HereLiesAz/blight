package com.example.ninthwardcanvas

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var currentPhotoPath: String? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        private const val FILE_CHOOSER_RESULT_CODE = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)

        requestPermissionsIfNecessary()

        setupWebView()
    }

    private fun requestPermissionsIfNecessary() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun setupWebView() {
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.setGeolocationEnabled(true)
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.setSupportMultipleWindows(true)

        webView.webViewClient = WebViewClient()

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, true, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                var takePictureIntent: Intent? = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (takePictureIntent?.resolveActivity(packageManager) != null) {
                    var photoFile: File? = null
                    try {
                        photoFile = createImageFile()
                        takePictureIntent.putExtra("PhotoPath", currentPhotoPath)
                    } catch (ex: IOException) {
                        // Error occurred while creating the File
                    }

                    if (photoFile != null) {
                        currentPhotoPath = "file:" + photoFile.absolutePath
                        val photoURI = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${applicationContext.packageName}.provider",
                            photoFile
                        )
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    } else {
                        takePictureIntent = null
                    }
                }

                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT)
                contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE)
                contentSelectionIntent.type = "image/*"

                val intentArray: Array<Intent?> = if (takePictureIntent != null) {
                    arrayOf(takePictureIntent)
                } else {
                    arrayOfNulls(0)
                }

                val chooserIntent = Intent(Intent.ACTION_CHOOSER)
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "Image Chooser")
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)

                startActivityForResult(chooserIntent, FILE_CHOOSER_RESULT_CODE)
                return true
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()

                newWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        val browserIntent = Intent(Intent.ACTION_VIEW, request?.url)
                        startActivity(browserIntent)
                        return true
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(browserIntent)
                        return true
                    }
                }
                return true
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val imageFileName = "JPEG_" + timeStamp + "_"
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            imageFileName,
            ".jpg",
            storageDir
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != FILE_CHOOSER_RESULT_CODE || filePathCallback == null) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }

        var results: Array<Uri>? = null

        if (resultCode == Activity.RESULT_OK) {
            if (data == null || data.data == null) {
                if (currentPhotoPath != null) {
                    results = arrayOf(Uri.parse(currentPhotoPath))
                }
            } else {
                val dataString = data.dataString
                if (dataString != null) {
                    results = arrayOf(Uri.parse(dataString))
                }
            }
        }

        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Reload the webview so it can pick up the new permissions
            webView.reload()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            onBackPressedDispatcher.onBackPressed()
        }
    }
}
