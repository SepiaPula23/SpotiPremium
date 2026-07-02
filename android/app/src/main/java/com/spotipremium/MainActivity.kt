package com.spotipremium

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spotipremium.data.AppDatabase
import com.spotipremium.data.Playlist
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.get(this) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var playlistAdapter: PlaylistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ImageButton>(R.id.searchBtn).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        val recycler = findViewById<RecyclerView>(R.id.playlistRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        playlistAdapter = PlaylistAdapter()
        recycler.adapter = playlistAdapter

        findViewById<TextView>(R.id.emptyText).visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        loadPlaylists()
    }

    private fun loadPlaylists() {
        scope.launch {
            val playlists = db.playlistDao().getAll()
            val downloadedCount = db.songDao().countDownloaded()
            withContext(Dispatchers.Main) {
                findViewById<TextView>(R.id.emptyText).visibility =
                    if (playlists.isEmpty()) View.VISIBLE else View.GONE
                findViewById<TextView>(R.id.downloadedInfo).text =
                    "$downloadedCount canciones descargadas"
                playlistAdapter.update(playlists)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    inner class PlaylistAdapter : RecyclerView.Adapter<PlaylistAdapter.Holder>() {
        private var items = listOf<Playlist>()

        fun update(list: List<Playlist>) {
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
                val intent = Intent(this@MainActivity, PlayerActivity::class.java)
                intent.putExtra("playlistId", pl.id)
                intent.putExtra("playlistName", pl.name)
                startActivity(intent)
            }
        }

        override fun getItemCount() = items.size

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title = v.findViewById<TextView>(R.id.itemTitle)!!
            val subtitle = v.findViewById<TextView>(R.id.itemSubtitle)!!
        }
    }
}
