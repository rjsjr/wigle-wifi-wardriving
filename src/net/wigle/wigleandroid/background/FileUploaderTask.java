package net.wigle.wigleandroid.background;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import net.wigle.wigleandroid.DBException;
import net.wigle.wigleandroid.DatabaseHelper;
import net.wigle.wigleandroid.ListActivity;
import net.wigle.wigleandroid.MainActivity;
import net.wigle.wigleandroid.Network;
import net.wigle.wigleandroid.R;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Environment;

public final class FileUploaderTask extends AbstractBackgroundTask {
  private final FileUploaderListener listener;
  private final boolean justWriteFile;
  private boolean writeWholeDb;
  private boolean writeRunOnly;
  
  private static final String COMMA = ",";
  private static final String NEWLINE = "\n";
  
  private static class CountStats {
    int byteCount;
    int lineCount;
  }
  
  public FileUploaderTask( final Context context, final DatabaseHelper dbHelper, final FileUploaderListener listener,
      final boolean justWriteFile ) {
    
    super( context, dbHelper, "HttpUL" );
    if ( listener == null ) {
      throw new IllegalArgumentException( "listener is null" );
    }
    
    this.listener = listener;
    this.justWriteFile = justWriteFile;
  }
  
  public void setWriteWholeDb() {
    this.writeWholeDb = true;
  }
  
  public void setWriteRunOnly() {
    this.writeRunOnly = true;
  }
  
  public void subRun() {
    try {      
      if ( justWriteFile ) {
        justWriteFile();
      }
      else {
        doRun();
      }
    }
    catch ( final InterruptedException ex ) {
      ListActivity.info( "file upload interrupted" );
    }
    catch ( final Throwable throwable ) {
      ListActivity.writeError( Thread.currentThread(), throwable, context );
      throw new RuntimeException( "FileUploaderTask throwable: " + throwable, throwable );
    }
    finally {
      // tell the listener
      listener.transferComplete();
    }
  }
  
  private void doRun() throws InterruptedException {
    final String username = getUsername();
    final String password = getPassword();
    Status status = validateUserPass( username, password );
    final Bundle bundle = new Bundle();
    if ( status == null ) {
      status = doUpload( username, password, bundle );
    }

    // tell the gui thread
    sendBundledMessage( status.ordinal(), bundle );
  }
  
  public static OutputStream getOutputStream(final Context context, final Bundle bundle, final Object[] fileFilename)
      throws IOException {
    final SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
    final String filename = "WigleWifi_" + fileDateFormat.format(new Date()) + ".csv.gz";
    
    String openString = filename;
    final boolean hasSD = ListActivity.hasSD();
    File file = null;
    bundle.putString( BackgroundGuiHandler.FILENAME, filename );
    if ( hasSD ) {
      final String filepath = MainActivity.safeFilePath( Environment.getExternalStorageDirectory() ) + "/wiglewifi/";
      final File path = new File( filepath );
      path.mkdirs();
      openString = filepath + filename;
      file = new File( openString );
      if ( ! file.exists() && hasSD ) {
        file.createNewFile();
      }
      bundle.putString( BackgroundGuiHandler.FILEPATH, filepath );
      bundle.putString( BackgroundGuiHandler.FILENAME, filename );
    }
    
    final FileOutputStream rawFos = hasSD ? new FileOutputStream( file )
      : context.openFileOutput( filename, Context.MODE_WORLD_READABLE );

    final GZIPOutputStream fos = new GZIPOutputStream( rawFos );
    fileFilename[0] = file;
    fileFilename[1] = filename;
    return fos;
  }
  
  private Status doUpload( final String username, final String password, final Bundle bundle ) 
      throws InterruptedException {    
    
    Status status = Status.UNKNOWN;
    
    try {
      final Object[] fileFilename = new Object[2];
      final OutputStream fos = getOutputStream( context, bundle, fileFilename );
      final File file = (File) fileFilename[0];
      final String filename = (String) fileFilename[1];

      // write file
      CountStats countStats = new CountStats();
      long maxId = writeFile( fos, bundle, countStats );      
      
      // don't upload empty files
      if ( countStats.lineCount == 0 && ! "bobzilla".equals(username) ) {
        return Status.EMPTY_FILE;
      }
      
      // show on the UI
      sendBundledMessage( Status.UPLOADING.ordinal(), bundle );

      long filesize = file != null ? file.length() : 0L;
      if ( filesize <= 0 ) {
        // find out how big the gzip'd file became
        final FileInputStream fin = context.openFileInput(filename);
        filesize = fin.available();
        fin.close();
        // ListActivity.info("filesize: " + filesize);
      }
      if ( filesize <= 0 ) {
        filesize = countStats.byteCount; // as an upper bound
      }

      // send file
      final boolean hasSD = ListActivity.hasSD();
      final FileInputStream fis = hasSD ? new FileInputStream( file ) 
        : context.openFileInput( filename ); 
      final Map<String,String> params = new HashMap<String,String>();
      
      params.put("observer", username);
      params.put("password", password);
      final SharedPreferences prefs = context.getSharedPreferences( ListActivity.SHARED_PREFS, 0);
      if ( prefs.getBoolean(ListActivity.PREF_DONATE, false) ) {
        params.put("donate","on");
      }
      final String response = HttpFileUploader.upload( 
        ListActivity.FILE_POST_URL, filename, "stumblefile", fis, 
        params, context.getResources(), getHandler(), filesize, context );
      
      if ( ! prefs.getBoolean(ListActivity.PREF_DONATE, false) ) {
        if ( response != null && response.indexOf("donate=Y") > 0 ) {
          final Editor editor = prefs.edit();
          editor.putBoolean( ListActivity.PREF_DONATE, true );
          editor.commit();
        }
      }
      
      if ( response != null && response.indexOf("uploaded successfully") > 0 ) {
        status = Status.SUCCESS;
        
        // save in the prefs        
        final Editor editor = prefs.edit();
        editor.putLong( ListActivity.PREF_DB_MARKER, maxId );
        editor.putLong( ListActivity.PREF_MAX_DB, maxId );
        editor.putLong( ListActivity.PREF_NETS_UPLOADED, dbHelper.getNetworkCount() );
        editor.commit();
      }
      else if ( response != null && response.indexOf("does not match login") > 0 ) {
        status = Status.BAD_LOGIN;
      }
      else {
        String error = null;
        if ( response != null && response.trim().equals( "" ) ) {
          error = "no response from server";
        } 
        else {
          error = "response: " + response;
        }
        ListActivity.error( error );
        bundle.putString( BackgroundGuiHandler.ERROR, error );
        status = Status.FAIL;
      }
    } 
    catch ( final InterruptedException ex ) {
      throw ex;
    }
    catch ( final FileNotFoundException ex ) {
      ex.printStackTrace();
      ListActivity.error( "file problem: " + ex, ex );
      ListActivity.writeError( this, ex, context, "Has data connection: " + hasDataConnection(context) );
      status = Status.EXCEPTION;
      bundle.putString( BackgroundGuiHandler.ERROR, "file problem: " + ex );
    }
    catch (ConnectException ex) {
      ex.printStackTrace();
      ListActivity.error( "connect problem: " + ex, ex );
      ListActivity.writeError( this, ex, context, "Has data connection: " + hasDataConnection(context) );
      status = Status.EXCEPTION;
      bundle.putString( BackgroundGuiHandler.ERROR, "connect problem: " + ex );
      if (! hasDataConnection(context)) {
        bundle.putString( BackgroundGuiHandler.ERROR, context.getString(R.string.no_data_conn) + ex);
      }
    }
    catch (UnknownHostException ex) {
      ex.printStackTrace();
      ListActivity.error( "dns problem: " + ex, ex );
      ListActivity.writeError( this, ex, context, "Has data connection: " + hasDataConnection(context) );
      status = Status.EXCEPTION;
      bundle.putString( BackgroundGuiHandler.ERROR, "dns problem: " + ex );
      if (! hasDataConnection(context)) {
        bundle.putString( BackgroundGuiHandler.ERROR, context.getString(R.string.no_data_conn) + ex);
      }
    }
    catch ( final IOException ex ) {
      ex.printStackTrace();
      ListActivity.error( "io problem: " + ex, ex );
      ListActivity.writeError( this, ex, context, "Has data connection: " + hasDataConnection(context) );
      status = Status.EXCEPTION;
      bundle.putString( BackgroundGuiHandler.ERROR, "io problem: " + ex );
    }
    catch ( final Exception ex ) {
      ex.printStackTrace();
      ListActivity.error( "ex problem: " + ex, ex );
      ListActivity.writeError( this, ex, context, "Has data connection: " + hasDataConnection(context) );
      status = Status.EXCEPTION;
      bundle.putString( BackgroundGuiHandler.ERROR, "ex problem: " + ex );
    }
    
    return status;
  }
  
  public static boolean hasDataConnection(final Context context) {
    final ConnectivityManager connMgr = 
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    final NetworkInfo wifi = connMgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
    final NetworkInfo mobile = connMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
    if (wifi.isAvailable()) {
      return true;
    } 
    if (mobile.isAvailable()) {
      return true;
    }
    return false;    
  }
  
  public Status justWriteFile() {
    Status status = null;
    final CountStats countStats = new CountStats();
    final Bundle bundle = new Bundle();
    
    try {
      OutputStream fos = null;
      try {
        fos = getOutputStream( context, bundle, new Object[2] );
        writeFile( fos, bundle, countStats );
        // show on the UI
        status = Status.WRITE_SUCCESS;
        sendBundledMessage( status.ordinal(), bundle );
      }
      finally {
        if ( fos != null ) {
          fos.close();
        }
      }
    }
    catch ( InterruptedException ex ) {
      ListActivity.info("justWriteFile interrupted: " + ex);
    }
    catch ( IOException ex ) {
      ex.printStackTrace();
      ListActivity.error( "io problem: " + ex, ex );
      ListActivity.writeError( this, ex, context );
      status = Status.EXCEPTION;
      bundle.putString( BackgroundGuiHandler.ERROR, "io problem: " + ex );
    }
    catch ( final Exception ex ) {
      ex.printStackTrace();
      ListActivity.error( "ex problem: " + ex, ex );
      ListActivity.writeError( this, ex, context );
      status = Status.EXCEPTION;
      bundle.putString( BackgroundGuiHandler.ERROR, "ex problem: " + ex );
    }
        
    return status;
  }
  
  private long writeFile( final OutputStream fos, final Bundle bundle, final CountStats countStats ) 
      throws IOException, NameNotFoundException, InterruptedException, DBException {
    
    final SharedPreferences prefs = context.getSharedPreferences( ListActivity.SHARED_PREFS, 0);
    long maxId = prefs.getLong( ListActivity.PREF_DB_MARKER, 0L );
    if ( writeWholeDb ) {
      maxId = 0;
    }
    else if ( writeRunOnly ) {
      // max id at startup
      maxId = prefs.getLong( ListActivity.PREF_MAX_DB, 0L );
    }
    ListActivity.info( "Writing file starting with observation id: " + maxId);
    final Cursor cursor = dbHelper.locationIterator( maxId );
    
    try {
      return writeFileWithCursor( fos, bundle, countStats, cursor );
    }
    finally {
      fos.close();
      cursor.close();
    }
  }
  
  private long writeFileWithCursor( final OutputStream fos, final Bundle bundle, final CountStats countStats, 
      final Cursor cursor ) throws IOException, NameNotFoundException, InterruptedException {

    final SharedPreferences prefs = context.getSharedPreferences( ListActivity.SHARED_PREFS, 0);
    long maxId = prefs.getLong( ListActivity.PREF_DB_MARKER, 0L );
    
    final long start = System.currentTimeMillis();
    final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    countStats.lineCount = 0;
    final int total = cursor.getCount();
    long fileWriteMillis = 0;
    long netMillis = 0;
    
    sendBundledMessage( Status.WRITING.ordinal(), bundle );
    
    final PackageManager pm = context.getPackageManager();
    final PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
    
    // name, version, header
    final String header = "WigleWifi-1.4"
        + ",appRelease=" + pi.versionName
        + ",model=" + android.os.Build.MODEL
        + ",release=" + android.os.Build.VERSION.RELEASE
        + ",device=" + android.os.Build.DEVICE
        + ",display=" + android.os.Build.DISPLAY
        + ",board=" + android.os.Build.BOARD
        + ",brand=" + android.os.Build.BRAND
        + "\n" 
        + "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n";
    writeFos( fos, header );
    
    // assume header is all byte per char
    countStats.byteCount = header.length();

    if ( total > 0 ) {
      CharBuffer charBuffer = CharBuffer.allocate( 256 );
      ByteBuffer byteBuffer = ByteBuffer.allocate( 256 ); // this ensures hasArray() is true
      final CharsetEncoder encoder = Charset.forName( ListActivity.ENCODING ).newEncoder();
      // don't stop when a goofy character is found
      encoder.onUnmappableCharacter( CodingErrorAction.REPLACE );
      final NumberFormat numberFormat = NumberFormat.getNumberInstance( Locale.US );
      // no commas in the comma-separated file
      numberFormat.setGroupingUsed( false );
      if ( numberFormat instanceof DecimalFormat ) {
        final DecimalFormat dc = (DecimalFormat) numberFormat;
        dc.setMaximumFractionDigits( 16 );
      }
      final StringBuffer stringBuffer = new StringBuffer();
      final FieldPosition fp = new FieldPosition(NumberFormat.INTEGER_FIELD);
      final Date date = new Date();
      // loop!
      for ( cursor.moveToFirst(); ! cursor.isAfterLast(); cursor.moveToNext() ) {
        if ( wasInterrupted() ) {
          throw new InterruptedException( "we were interrupted" );
        }
        // _id,bssid,level,lat,lon,time
        final long id = cursor.getLong(0);
        if ( id > maxId ) {
          maxId = id;
        }
        final String bssid = cursor.getString(1);
        final long netStart = System.currentTimeMillis();
        final Network network = dbHelper.getNetwork( bssid );
        netMillis += System.currentTimeMillis() - netStart;
        if ( network == null ) {
          // weird condition, skipping
          ListActivity.error("network not in database: " + bssid );
          continue;
        }
        
        countStats.lineCount++;
        String ssid = network.getSsid();
        if ( ssid.indexOf( COMMA ) >= 0 ) {
          // comma isn't a legal ssid character, but just in case
          ssid = ssid.replaceAll( COMMA, "_" ); 
        }
        // ListActivity.debug("writing network: " + ssid );
        
        // reset the buffers
        charBuffer.clear();
        byteBuffer.clear();
        // fill in the line
        try {
          charBuffer.append( network.getBssid() );
          charBuffer.append( COMMA );
          // ssid = "ronan stephensÕs iMac";
          charBuffer.append( ssid );
          charBuffer.append( COMMA );
          charBuffer.append( network.getCapabilities() );
          charBuffer.append( COMMA );
          date.setTime( cursor.getLong(7) );
          singleCopyDateFormat( dateFormat, stringBuffer, charBuffer, fp, date );
          charBuffer.append( COMMA );
          Integer channel = network.getChannel();
          if ( channel == null ) {
            channel = network.getFrequency();
          }
          singleCopyNumberFormat( numberFormat, stringBuffer, charBuffer, fp, channel );
          charBuffer.append( COMMA );
          singleCopyNumberFormat( numberFormat, stringBuffer, charBuffer, fp, cursor.getInt(2) );
          charBuffer.append( COMMA );
          singleCopyNumberFormat( numberFormat, stringBuffer, charBuffer, fp, cursor.getDouble(3) );
          charBuffer.append( COMMA );
          singleCopyNumberFormat( numberFormat, stringBuffer, charBuffer, fp, cursor.getDouble(4) );
          charBuffer.append( COMMA );
          singleCopyNumberFormat( numberFormat, stringBuffer, charBuffer, fp, cursor.getDouble(5) );
          charBuffer.append( COMMA );
          singleCopyNumberFormat( numberFormat, stringBuffer, charBuffer, fp, cursor.getDouble(6) );
          charBuffer.append( COMMA );
          charBuffer.append( network.getType().name() );          
          charBuffer.append( NEWLINE );
        }
        catch ( BufferOverflowException ex ) {
          ListActivity.info("buffer overflow: " + ex, ex );
          // double the buffer
          charBuffer = CharBuffer.allocate( charBuffer.capacity() * 2 );
          byteBuffer = ByteBuffer.allocate( byteBuffer.capacity() * 2 );
          // try again
          cursor.moveToPrevious();
          continue;
        }
        
        // tell the encoder to stop here and to start at the beginning
        charBuffer.flip();

        // do the encoding
        encoder.reset();
        encoder.encode( charBuffer, byteBuffer, true );
        encoder.flush( byteBuffer );
        // byteBuffer = encoder.encode( charBuffer );  (old way)
        
        // figure out where in the byteBuffer to stop
        final int end = byteBuffer.position();
        final int offset = byteBuffer.arrayOffset();
        //if ( end == 0 ) {
          // if doing the encode without giving a long-term byteBuffer (old way), the output
          // byteBuffer position is zero, and the limit and capacity are how long to write for.
        //  end = byteBuffer.limit();
        //}
        
        // ListActivity.info("buffer: arrayOffset: " + byteBuffer.arrayOffset() + " limit: " + byteBuffer.limit()
        //     + " capacity: " + byteBuffer.capacity() + " pos: " + byteBuffer.position() + " end: " + end
        //     + " result: " + result );
        final long writeStart = System.currentTimeMillis();
        fos.write(byteBuffer.array(), offset, end+offset );
        fileWriteMillis += System.currentTimeMillis() - writeStart;

        countStats.byteCount += end;

        // update UI
        final int percentDone = (countStats.lineCount * 1000) / total;
        sendPercentTimesTen( percentDone, bundle );        
      }
    }
    
    ListActivity.info("wrote file in: " + (System.currentTimeMillis() - start) + "ms. fileWriteMillis: "
        + fileWriteMillis + " netmillis: " + netMillis );
    
    return maxId;
  }
  
  public static void writeFos( final OutputStream fos, final String data ) throws IOException, UnsupportedEncodingException {
    if ( data != null ) {
      fos.write( data.getBytes( ListActivity.ENCODING ) );
    }
  }
  
  private void singleCopyNumberFormat( final NumberFormat numberFormat, final StringBuffer stringBuffer, 
      final CharBuffer charBuffer, final FieldPosition fp, final int number ) {
    stringBuffer.setLength( 0 );
    numberFormat.format( number, stringBuffer, fp );
    stringBuffer.getChars(0, stringBuffer.length(), charBuffer.array(), charBuffer.position() );
    charBuffer.position( charBuffer.position() + stringBuffer.length() );
  }
  
  private void singleCopyNumberFormat( final NumberFormat numberFormat, final StringBuffer stringBuffer, 
      final CharBuffer charBuffer, final FieldPosition fp, final double number ) {
    stringBuffer.setLength( 0 );
    numberFormat.format( number, stringBuffer, fp );
    stringBuffer.getChars(0, stringBuffer.length(), charBuffer.array(), charBuffer.position() );
    charBuffer.position( charBuffer.position() + stringBuffer.length() );
  }
  
  private void singleCopyDateFormat( final DateFormat dateFormat, final StringBuffer stringBuffer, 
      final CharBuffer charBuffer, final FieldPosition fp, final Date date ) {
    stringBuffer.setLength( 0 );
    dateFormat.format( date, stringBuffer, fp );
    stringBuffer.getChars(0, stringBuffer.length(), charBuffer.array(), charBuffer.position() );
    charBuffer.position( charBuffer.position() + stringBuffer.length() );
  }
   
}
