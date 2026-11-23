package javax.microedition.io.file;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;

/**
 * Compile-time stub of the JSR-75 optional package (FileConnection 1.0).
 *
 * The WTK/MicroEmulator boot classpath this project compiles against contains
 * CLDC and MIDP only. The handset has the real interface; this stub exists so
 * tg.plat.FileStore can be compiled and checked without pulling an optional
 * package into the artifact. It is a bootclasspath entry, never packaged.
 */
public interface FileConnection
{
    boolean exists();
    boolean isDirectory();
    boolean isOpen();
    long fileSize();
    void create() throws java.io.IOException;
    void delete() throws java.io.IOException;
    void mkdir() throws java.io.IOException;
    void setHidden(boolean hidden) throws java.io.IOException;
    Enumeration list() throws java.io.IOException;
    Enumeration list(String filter, boolean includeHidden)
        throws java.io.IOException;
    void truncate(long byteOffset) throws java.io.IOException;
    void rename(String newName) throws java.io.IOException;
    InputStream openInputStream() throws java.io.IOException;
    OutputStream openOutputStream() throws java.io.IOException;
    long lastModified();
    String getName();
    String getPath();
    String getURL();
    boolean canRead();
    boolean canWrite();
    void close() throws java.io.IOException;
}
