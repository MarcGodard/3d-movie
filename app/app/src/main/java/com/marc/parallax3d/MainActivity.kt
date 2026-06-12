package com.marc.parallax3d

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile

/** Library: persisted movie folder, lists .p3d.mp4 files, reads sidecars. */
class MainActivity : AppCompatActivity() {

    private data class Entry(val name: String, val uri: Uri, val sidecar: String?)

    private val entries = mutableListOf<Entry>()
    private lateinit var adapter: ArrayAdapter<String>
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }

    private val openMovie = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            play(uri, sidecar = null)
        }
    }

    private val openFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("folder", uri.toString()).apply()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        val list = findViewById<ListView>(R.id.movieList)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            entries.getOrNull(pos)?.let { play(it.uri, it.sidecar) }
        }

        findViewById<Button>(R.id.openButton).setOnClickListener {
            openMovie.launch(arrayOf("video/mp4"))
        }
        findViewById<Button>(R.id.folderButton).setOnClickListener {
            openFolder.launch(null)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entries.clear()
        adapter.clear()
        val folder = prefs.getString("folder", null)?.toUri() ?: run {
            findViewById<View>(R.id.emptyText).visibility = View.VISIBLE
            return
        }
        val dir = DocumentFile.fromTreeUri(this, folder) ?: return
        val files = dir.listFiles()
        // Sidecar lookup by name: movie.p3d.mp4 -> movie.p3d.p3d.json
        val byName = files.associateBy { it.name ?: "" }
        for (f in files.sortedBy { it.name }) {
            val name = f.name ?: continue
            if (!name.endsWith(".mp4")) continue
            val sidecarName = name.removeSuffix(".mp4") + ".p3d.json"
            val sidecar = byName[sidecarName]?.let { sc ->
                runCatching {
                    contentResolver.openInputStream(sc.uri)?.use {
                        it.readBytes().decodeToString()
                    }
                }.getOrNull()
            }
            entries.add(Entry(name.removeSuffix(".mp4"), f.uri, sidecar))
            adapter.add(name.removeSuffix(".mp4"))
        }
        findViewById<View>(R.id.emptyText).visibility =
            if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun play(uri: Uri, sidecar: String?) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            data = uri
            if (sidecar != null) putExtra(PlayerActivity.EXTRA_SIDECAR, sidecar)
        })
    }
}
