package com.mosaic.gallery

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.widget.Toast

/** Same sharing and URI grants in the main gallery and people folders. */
object MediaSharing {
    fun share(activity:Activity,photos:List<PhotoRecord>) {
        val chosen=photos.distinctBy{it.uri};if(chosen.isEmpty())return
        if(chosen.size>200){Toast.makeText(activity,"Share up to 200 photos at a time.",Toast.LENGTH_LONG).show();return}
        val uris=ArrayList(chosen.map{it.uri})
        runCatching{activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply{
            type=when{chosen.all{it.isVideo}->"video/*";chosen.none{it.isVideo}->"image/*";else->"*/*"}
            putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris)
            clipData=ClipData.newUri(activity.contentResolver,"Photos",uris.first()).apply{uris.drop(1).forEach{addItem(ClipData.Item(it))}}
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },"Share selected photos"))}.onFailure{Toast.makeText(activity,"Could not share the selected photos.",Toast.LENGTH_SHORT).show()}
    }
}
