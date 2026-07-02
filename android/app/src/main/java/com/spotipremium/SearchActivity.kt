package com.spotipremium

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spotipremium.api.SpotifyClient
import com.spotipremium.data.AppDatabase
import com.spotipremium.data.Playlist
import com.spotipremium.data.Song
import kotlinx.coroutines.*

class SearchActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val spotify = SpotifyClient()
    private val db by lazy { AppDatabase.get(this) }

    private lateinit var adapter: SearchAdapter
    private var searchResults = listOf<SpotifyClient.PlaylistResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }

        val searchInput = findViewById<EditText>(R.id.searchInput)
        val serverIpInput = findViewById<EditText>(R.id.serverIpInput)

        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val savedIp = prefs.getString("serverIp", "") ?: ""
        serverIpInput.setText(savedIp)

        findViewById<Button>(R.id.searchBtn).setOnClickListener {
            val q = searchInput.text.toString().trim()
            if (q.isNotEmpty()) {
                val ip = serverIpInput.text.toString().trim()
                spotify.serverUrl = if (ip.isBlank()) "" else "http://$ip:8000"
                prefs.edit().putString("serverIp", ip).apply()
                searchPlaylists(q)
            }
        }

        findViewById<Button>(R.id.urlBtn).setOnClickListener {
            val url = searchInput.text.toString().trim()
            if (url.isNotEmpty()) {
                val ip = serverIpInput.text.toString().trim()
                spotify.serverUrl = if (ip.isBlank()) "" else "http://$ip:8000"
                prefs.edit().putString("serverIp", ip).apply()
                importByUrl(url)
            }
        }

        val recycler = findViewById<RecyclerView>(R.id.resultsRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = SearchAdapter()
        recycler.adapter = adapter
    }

    private fun searchPlaylists(query: String) {
        findViewById<TextView>(R.id.statusText).text = "Buscando..."
        scope.launch {
            try {
                val results = spotify.searchPlaylists(query)
                withContext(Dispatchers.Main) {
                    searchResults = results
                    adapter.update(results)
                    findViewById<TextView>(R.id.statusText).text =
                        if (results.isEmpty()) "Sin resultados" else "${results.size} resultados"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.statusText).text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun importByUrl(input: String) {
        findViewById<TextView>(R.id.statusText).text = "Conectando con Spotify..."
        scope.launch {
            try {
                val detail = spotify.getPlaylist(input)
                withContext(Dispatchers.Main) {
                    if (detail != null) {
                        saveAndOpen(detail)
                    } else {
                        findViewById<TextView>(R.id.statusText).text =
                            "No se pudo importar. Verifica la URL."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.statusText).text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun saveAndOpen(detail: SpotifyClient.PlaylistDetail) {
        scope.launch {
            db.playlistDao().insert(Playlist(
                id = detail.id, name = detail.name,
                description = detail.description, imageUrl = detail.imageUrl,
                songCount = detail.songs.size
            ))
            db.songDao().deleteByPlaylist(detail.id)
            db.songDao().insertAll(detail.songs.map { s ->
                s.copy(playlistId = detail.id)
            })

            withContext(Dispatchers.Main) {
                startActivity(Intent(this@SearchActivity, PlayerActivity::class.java).apply {
                    putExtra("playlistId", detail.id)
                    putExtra("playlistName", detail.name)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
                finish()
            }
        }
    }

    private inner class SearchAdapter : RecyclerView.Adapter<SearchAdapter.Holder>() {
        private var items = listOf<SpotifyClient.PlaylistResult>()

        fun update(list: List<SpotifyClient.PlaylistResult>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_playlist, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, i: Int) {
            val pl = items[i]
            h.title.text = pl.name
            h.subtitle.text = "${pl.songCount} canciones"
            h.itemView.setOnClickListener {
                Toast.makeText(this@SearchActivity, "Importando...", Toast.LENGTH_SHORT).show()
                importByUrl(pl.id)
            }
        }

        override fun getItemCount() = items.size

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title = v.findViewById<TextView>(R.id.itemTitle)!!
            val subtitle = v.findViewById<TextView>(R.id.itemSubtitle)!!
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
