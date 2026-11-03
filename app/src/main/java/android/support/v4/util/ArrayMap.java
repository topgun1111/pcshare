package android.support.v4.util;

/**
 * Stand-in for the old support-library class that the PDFium binding (com.shockwave.pdfium.PdfDocument) still imports.
 * The real support-v4 is excluded in app/build.gradle.kts (it clashes with AndroidX); the binding only needs a Map.
 */
public class ArrayMap<K, V> extends java.util.HashMap<K, V> {
    public ArrayMap() { super(); }
}
