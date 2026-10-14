package android.content;

import android.os.IInterface;

/**
 * A compile-time stub to expose the hidden {@link android.content.IContentProvider} type.
 * <p>
 * This empty interface satisfies the compiler type-checking requirements.
 * <p>
 * During build, this stub is stripped out of the final APK, at runtime, the Android classloader prioritizes framework classes,
 * it will see that {@link android.content.IContentProvider} was used and use the real OS implementation (hidden interface in Android SDK).
 */
public interface IContentProvider extends IInterface
{

}