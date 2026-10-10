package com.mosaic.gallery

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.text.*
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

/** Local identity links use Android lookup URIs; names alone never identify a contact. */
object ContactNames {
    data class Contact(val lookup:String,val name:String){override fun toString()=name}
    data class Choice(val name:String,val contact:Contact?)
    private fun allowed(context:Context)=context.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED
    private val projection=arrayOf(ContactsContract.Contacts._ID,ContactsContract.Contacts.LOOKUP_KEY,ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
    fun read(context:Context,text:String,signal:CancellationSignal=CancellationSignal()):List<Contact> {
        if(!allowed(context))return emptyList()
        val uri=if(text.isBlank())ContactsContract.Contacts.CONTENT_URI else Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI,text.trim()).buildUpon()
            .appendQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY,ContactsContract.Directory.DEFAULT.toString()).build()
        return query(context,uri,signal)
    }
    private fun query(context:Context,uri:Uri,signal:CancellationSignal):List<Contact> = context.contentResolver.query(uri,projection,null,null,ContactsContract.Contacts.DISPLAY_NAME_PRIMARY+" ASC",signal)?.use { c->
        val contacts=linkedMapOf<String,Contact>();while(c.moveToNext() && contacts.size<100){signal.throwIfCanceled();val name=c.getString(2)?.trim().orEmpty();val key=c.getString(1)
            if(name.isNotEmpty() && !key.isNullOrBlank()){val lookup=ContactsContract.Contacts.getLookupUri(c.getLong(0),key).toString();contacts[lookup]=Contact(lookup,name)}};contacts.values.toList()
    }.orEmpty()
    fun resolve(context:Context,contact:Contact,signal:CancellationSignal=CancellationSignal()):Contact? {
        if(!allowed(context) || !valid(contact.lookup))return null
        // Querying the lookup URI lets the provider resolve contact IDs changed by aggregation/sync.
        return query(context,Uri.parse(contact.lookup),signal).firstOrNull()
    }
    fun valid(lookup:String):Boolean {val uri=Uri.parse(lookup);return uri.scheme=="content" && uri.authority==ContactsContract.AUTHORITY && uri.pathSegments.take(2)==listOf("contacts","lookup") && uri.pathSegments.size>=3}
    fun open(activity:Activity,contact:Contact){if(valid(contact.lookup))runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(contact.lookup)))}.onFailure{Toast.makeText(activity,"Contact is unavailable. You can remove its link when editing the name.",Toast.LENGTH_LONG).show()}}
}
class PeopleNames(private val activity:Activity) {
    companion object{const val PERMISSION=4202}
    private val worker=Executors.newSingleThreadExecutor();private val handler=Handler(Looper.getMainLooper())
    private var dialog:AlertDialog?=null;private var input:EditText?=null;private var contacts:Button?=null
    private var list:ListView?=null;private var selectContact:((ContactNames.Contact)->Unit)?=null
    private var portrait:android.graphics.Bitmap?=null
    private val avatars=android.util.LruCache<String,android.graphics.Bitmap>(32)
    private val avatarRequests=mutableSetOf<String>()
    private var readingPermission=false
    private val photoOffer=ContactPhotoOffer(activity)
    val requestingPermission get()=readingPermission || photoOffer.requestingPermission
    private var signal:CancellationSignal?=null;private var epoch=0;private var closed=false
    private val refresh=Runnable{suggest()}
    fun show(value:String,contact:ContactNames.Contact?=null,preview:android.graphics.Bitmap?=null,face:FaceObservation?=null,photoKey:GroupRules.Key?=null,group:Long?=null,singleFace:Boolean=false,save:(ContactNames.Choice)->Unit) {
        dismiss();var linked=contact
        val root=GalleryStyle.panelRoot(activity)
        root.addView(GalleryStyle.text(activity,"Name this person",22f))
        root.addView(GalleryStyle.text(activity,"Choose a contact or type any name, then save.",14f,GalleryStyle.muted(activity)),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(12)})
        if(!ContactRecognition.allowed(activity))root.addView(GalleryStyle.text(activity,"Allow contacts to suggest names. Automatic contact-photo matching is optional and can be controlled in Settings > Privacy.",13f,GalleryStyle.muted(activity)))
        if(preview!=null && !preview.isRecycled && face!=null){
            val size=maxOf(preview.width,preview.height);val ratio=minOf(1f,640f/size)
            val scaled=android.graphics.Bitmap.createScaledBitmap(preview,(preview.width*ratio).toInt().coerceAtLeast(1),(preview.height*ratio).toInt().coerceAtLeast(1),true)
            portrait=if(scaled===preview)preview.copy(android.graphics.Bitmap.Config.ARGB_8888,false)else scaled
            root.addView(FaceCrop(activity).apply{GalleryStyle.roundFace(this);show(portrait,face)},LinearLayout.LayoutParams(dp(76),dp(76)).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL;bottomMargin=dp(12)})
        }
        val field=EditText(activity).apply{hint="Search contacts or enter a name";setSingleLine();setTextColor(GalleryStyle.textColor(activity));setHintTextColor(GalleryStyle.muted(activity));setText(value);filters=arrayOf(InputFilter.LengthFilter(60));contentDescription="Person name"};input=field
        root.addView(field,LinearLayout.LayoutParams(-1,dp(56)))
        val linkText=GalleryStyle.text(activity,"",13f,GalleryStyle.muted(activity));root.addView(linkText)
        val linkRow=LinearLayout(activity)
        fun showLink(){linkText.text=linked?.let{"Contact: ${it.name}"}?:"";linkRow.visibility=if(linked==null)View.GONE else View.VISIBLE}
        linkRow.addView(GalleryStyle.button(activity,"View contact"){linked?.let{ContactNames.open(activity,it)}},LinearLayout.LayoutParams(0,dp(44),1f))
        linkRow.addView(GalleryStyle.button(activity,"Remove link"){linked=null;showLink()},LinearLayout.LayoutParams(0,dp(44),1f).apply{leftMargin=dp(8)})
        root.addView(linkRow);showLink()
        contacts=GalleryStyle.button(activity,"Show phone contacts"){requestContacts()}.also{root.addView(it,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)})}
        root.addView(GalleryStyle.text(activity,"Contacts",14f,GalleryStyle.muted(activity)),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12);bottomMargin=dp(8)})
        val contactList=ListView(activity).apply{divider=null;isVerticalScrollBarEnabled=false};list=contactList
        root.addView(contactList,LinearLayout.LayoutParams(-1,minOf(dp(280),activity.resources.displayMetrics.heightPixels/4)))
        selectContact={next->linked=next;field.setText(next.name);field.setSelection(field.length());showLink()}
        contactList.setOnItemClickListener{parent,_,position,_->(parent.getItemAtPosition(position) as? ContactNames.Contact)?.let{selectContact?.invoke(it)}}
        val actions=LinearLayout(activity)
        actions.addView(GalleryStyle.button(activity,"Cancel"){dismiss()},LinearLayout.LayoutParams(0,dp(48),1f))
        actions.addView(GalleryStyle.button(activity,"Save",true){val name=field.text.toString().trim();if(name.isEmpty())field.error="Enter a name"else{save(ContactNames.Choice(name,linked));dismiss();linked?.let{photoOffer.offer(it,photoKey,group,singleFace)}}},LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(10)})
        val body=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        while(root.childCount>0){val child=root.getChildAt(0);root.removeView(child);body.addView(child)}
        root.addView(ScrollView(activity).apply{isFillViewport=false;addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        root.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
        field.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun afterTextChanged(s:Editable?){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){schedule()}})
        dialog=GalleryStyle.dialog(activity,root).apply{setOnDismissListener{if(dialog===this){epoch++;signal?.cancel();handler.removeCallbacks(refresh);input=null;contacts=null;list=null;selectContact=null;portrait?.recycle();portrait=null;dialog=null}}}
        val initialHeight=minOf(dp(620),activity.resources.displayMetrics.heightPixels*85/100)
        dialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog?.window?.setLayout(minOf(activity.resources.displayMetrics.widthPixels-dp(32),dp(560)),initialHeight)
        var appliedHeight=initialHeight
        root.viewTreeObserver.addOnGlobalLayoutListener{
            if(dialog?.isShowing==true){val visible=android.graphics.Rect();root.getWindowVisibleDisplayFrame(visible)
                val height=minOf(initialHeight,visible.height()*90/100).coerceAtLeast(dp(240))
                if(visible.height()>0 && height!=appliedHeight){appliedHeight=height;dialog?.window?.setLayout(minOf(activity.resources.displayMetrics.widthPixels-dp(32),dp(560)),height)}
            }
        }
        updatePermission();schedule()
    }
    private fun requestContacts(){readingPermission=true;activity.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS),PERMISSION)}
    fun permissionResult(request:Int):Boolean{if(photoOffer.permissionResult(request))return true;if(request!=PERMISSION)return false;readingPermission=false;updatePermission();schedule();ContactRecognition.invalidate(activity);AutoPeople.ensure(activity,1_000);return true}
    private fun updatePermission(){contacts?.visibility=if(activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED)View.GONE else View.VISIBLE}
    private fun schedule(){epoch++;signal?.cancel();handler.removeCallbacks(refresh);if(dialog?.isShowing==true)handler.postDelayed(refresh,120)}
    private fun suggest(){
        val field=input?:return;val text=field.text.toString();val token=epoch;val cancel=CancellationSignal();signal=cancel
        worker.execute{val result=runCatching{ContactNames.read(activity,text,cancel)}.getOrDefault(emptyList());activity.runOnUiThread{
            if(!closed && token==epoch && !cancel.isCanceled && dialog?.isShowing==true && input===field){
                list?.adapter=object:ArrayAdapter<ContactNames.Contact>(activity,android.R.layout.simple_list_item_1,result){
                    override fun getView(position:Int,recycled:View?,parent:android.view.ViewGroup):View{
                        val item=getItem(position)!!;return LinearLayout(activity).apply{
                            gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(2),dp(6),dp(2),dp(6))
                            val avatar=FrameLayout(activity).apply{GalleryStyle.roundFace(this);background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(GalleryStyle.surface(activity))}}
                            avatar.addView(GalleryStyle.text(activity,item.name.take(1).uppercase(),18f,GalleryStyle.accent(activity)).apply{gravity=android.view.Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
                            val picture=ImageView(activity).apply{scaleType=ImageView.ScaleType.CENTER_CROP};avatar.addView(picture,FrameLayout.LayoutParams(-1,-1))
                            addView(avatar,LinearLayout.LayoutParams(dp(40),dp(40)))
                            avatars.get(item.lookup)?.let{picture.setImageBitmap(it)}?:run{
                                if(avatarRequests.add(item.lookup))worker.execute{
                                    val bitmap=if(closed || token!=epoch || activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)null else runCatching{
                                        android.provider.ContactsContract.Contacts.openContactPhotoInputStream(activity.contentResolver,Uri.parse(item.lookup),false)?.use{android.graphics.BitmapFactory.decodeStream(it)}
                                    }.getOrNull()
                                    activity.runOnUiThread{avatarRequests.remove(item.lookup);if(!closed && token==epoch && dialog?.isShowing==true){bitmap?.let{avatars.put(item.lookup,it);picture.setImageBitmap(it)}}else bitmap?.recycle()}
                                }
                            }
                            addView(GalleryStyle.text(activity,item.name,16f).apply{gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(12),0,0,0);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,dp(44),1f))
                        }
                    }
                }
            }
        }}
    }
    fun dismiss(){photoOffer.dismiss();dialog?.dismiss();epoch++;signal?.cancel();handler.removeCallbacks(refresh)}
    fun close(){closed=true;photoOffer.close();dismiss();avatars.evictAll();worker.shutdownNow()}
    private fun dp(value:Int)=GalleryStyle.dp(activity,value)
}
