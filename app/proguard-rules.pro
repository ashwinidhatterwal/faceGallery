# Saved People metadata uses private Java serialization, never external input.
-keepnames class com.mosaic.gallery.** implements java.io.Serializable
-keepclassmembers class com.mosaic.gallery.** implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
}
