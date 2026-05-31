package com.example.longshotapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var rvGallery: RecyclerView
    private lateinit var btnStartService: Button
    private lateinit var btnSettings: Button
    private lateinit var btnNewFolder: Button
    private lateinit var tvCurrentFolder: TextView
    private lateinit var bottomActionPanel: LinearLayout
    private lateinit var adapter: GalleryAdapter

    private lateinit var rootDir: File
    private lateinit var currentDir: File
    private var allFiles = mutableListOf<File>()

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA_INTENT", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            moveTaskToBack(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        if (!rootDir.exists()) rootDir.mkdirs()
        currentDir = rootDir

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        rvGallery = findViewById(R.id.rv_gallery)
        btnStartService = findViewById(R.id.btn_start_service)
        btnSettings = findViewById(R.id.btn_settings)
        btnNewFolder = findViewById(R.id.btn_new_folder)
        tvCurrentFolder = findViewById(R.id.tv_current_folder)
        bottomActionPanel = findViewById(R.id.bottom_action_panel)

        adapter = GalleryAdapter { selectionCount ->
            bottomActionPanel.visibility = if (selectionCount > 0) View.VISIBLE else View.GONE
        }
        rvGallery.layoutManager = GridLayoutManager(this, 3)
        rvGallery.adapter = adapter

        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnStartService.setOnClickListener { checkAndStartService() }

        btnNewFolder.setOnClickListener {
            showTextInputDialog("New Folder Name") { name ->
                val f = File(currentDir, name)
                if (!f.exists()) f.mkdirs()
                loadCurrentFolder()
            }
        }

        findViewById<Button>(R.id.btn_action_delete).setOnClickListener {
            adapter.selectedFiles.forEach { it.deleteRecursively() }
            adapter.clearSelection()
            loadCurrentFolder()
        }

        findViewById<Button>(R.id.btn_action_share).setOnClickListener { shareSelected() }
        findViewById<Button>(R.id.btn_action_move).setOnClickListener { showMoveCopyDialog(isCopy = false) }
        findViewById<Button>(R.id.btn_action_copy).setOnClickListener { showMoveCopyDialog(isCopy = true) }

        handleIncomingShareIntent()
    }

    override fun onResume() {
        super.onResume()
        loadCurrentFolder()
    }

    private fun handleIncomingShareIntent() {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            uri?.let {
                try {
                    val inputStream = contentResolver.openInputStream(it)
                    val destFile = File(rootDir, "Shared_${System.currentTimeMillis()}.jpg")
                    val outputStream = FileOutputStream(destFile)
                    inputStream?.copyTo(outputStream)
                    inputStream?.close()
                    outputStream.close()
                    Toast.makeText(this, "Image imported!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to import", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadCurrentFolder() {
        tvCurrentFolder.text = if (currentDir == rootDir) "All Folders" else currentDir.name
        val files = currentDir.listFiles()?.toList()?.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified() })) ?: emptyList()
        
        allFiles.clear()
        if (currentDir != rootDir) allFiles.add(File(currentDir, "..")) // Back button
        allFiles.addAll(files)

        adapter.submitList(allFiles)
    }

    private fun showMoveCopyDialog(isCopy: Boolean) {
        val folders = rootDir.listFiles { f -> f.isDirectory }?.map { it.name }?.toTypedArray() ?: emptyArray()
        val options = arrayOf("Root") + folders
        
        AlertDialog.Builder(this)
            .setTitle(if (isCopy) "Copy To..." else "Move To...")
            .setItems(options) { _, which ->
                val destDir = if (which == 0) rootDir else File(rootDir, options[which])
                adapter.selectedFiles.forEach { file ->
                    if (isCopy) file.copyRecursively(File(destDir, file.name), true)
                    else file.renameTo(File(destDir, file.name))
                }
                adapter.clearSelection()
                loadCurrentFolder()
            }.show()
    }

    private fun shareSelected() {
        val uris = adapter.selectedFiles.filter { !it.isDirectory }.map { file ->
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        }
        if (uris.isEmpty()) return
        
        val shareIntent = Intent().apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = "image/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Images"))
        adapter.clearSelection()
    }

    private fun showTextInputDialog(title: String, defaultText: String = "", onConfirm: (String) -> Unit) {
        val input = EditText(this).apply { text.append(defaultText) }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> onConfirm(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun checkAndStartService() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val expectedComponent = ComponentName(this, LongshotAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        if (!enabledServices.contains(expectedComponent)) {
            Toast.makeText(this, "Please enable Accessibility Service", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // --- INNER ADAPTER ---
    inner class GalleryAdapter(private val onSelectionChange: (Int) -> Unit) : RecyclerView.Adapter<GalleryAdapter.VH>() {
        var items = listOf<File>()
        val selectedFiles = mutableSetOf<File>()
        var isSelectionMode = false

        fun submitList(newItems: List<File>) {
            items = newItems
            notifyDataSetChanged()
        }
        
        fun clearSelection() {
            selectedFiles.clear()
            isSelectionMode = false
            onSelectionChange(0)
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img = v.findViewById<ImageView>(R.id.img_thumb)
            val overlay = v.findViewById<View>(R.id.view_overlay)
            val name = v.findViewById<TextView>(R.id.tv_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = items[position]
            val isBackBtn = file.name == ".."

            holder.name.text = if (isBackBtn) "Go Back" else file.name
            
            if (isBackBtn || file.isDirectory) {
                holder.img.setImageResource(android.R.drawable.ic_menu_crop) // Placeholder folder icon
            } else {
                holder.img.setImageURI(Uri.fromFile(file))
            }

            holder.overlay.visibility = if (selectedFiles.contains(file)) View.VISIBLE else View.GONE

            holder.itemView.setOnClickListener {
                if (isBackBtn) {
                    currentDir = rootDir
                    loadCurrentFolder()
                    return@setOnClickListener
                }

                if (isSelectionMode) {
                    if (selectedFiles.contains(file)) selectedFiles.remove(file) else selectedFiles.add(file)
                    if (selectedFiles.isEmpty()) isSelectionMode = false
                    onSelectionChange(selectedFiles.size)
                    notifyItemChanged(position)
                } else {
                    if (file.isDirectory) {
                        currentDir = file
                        loadCurrentFolder()
                    } else {
                        // Open in Fullscreen gallery app!
                        val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(intent)
                    }
                }
            }

            holder.itemView.setOnLongClickListener {
                if (!isBackBtn) {
                    if (!isSelectionMode) isSelectionMode = true
                    selectedFiles.add(file)
                    onSelectionChange(selectedFiles.size)
                    notifyDataSetChanged()
                }
                true
            }
        }
    }
}
