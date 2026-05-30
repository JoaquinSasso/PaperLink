
package com.joasasso.paperlink.data.local

import androidx.room.Entity
import androidx.room.Fts4

@Entity(tableName = "paper_links_fts")
@Fts4(contentEntity = PaperLink::class)
data class PaperLinkFts(
    val code: String,
    val displayName: String?,
    val note: String?
)