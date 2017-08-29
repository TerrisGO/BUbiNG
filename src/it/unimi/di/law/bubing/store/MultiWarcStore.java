package it.unimi.di.law.bubing.store;

/*		 
 * Copyright (C) 2012-2013 Paolo Boldi, Massimo Santini, and Sebastiano Vigna 
 *
 *  This program is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the Free
 *  Software Foundation; either version 3 of the License, or (at your option)
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses/>.
 *
 */

import it.unimi.di.law.bubing.RuntimeConfiguration;
import it.unimi.di.law.warc.io.ParallelBufferedWarcWriter;
import it.unimi.di.law.warc.records.HttpResponseWarcRecord;
import it.unimi.di.law.warc.records.WarcHeader;
import it.unimi.dsi.fastutil.io.FastBufferedOutputStream;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.Date;
import java.text.SimpleDateFormat;


import org.apache.commons.codec.binary.Hex;
import org.apache.http.HttpResponse;
import org.apache.http.message.HeaderGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//RELEASE-STATUS: DIST

/** A {@link Store} implementation using the {@link it.unimi.di.law.warc} package. */

public class MultiWarcStore implements Closeable, Store {
	private final static Logger LOGGER = LoggerFactory.getLogger( WarcStore.class );

	public final int OUTPUT_STREAM_BUFFER_SIZE = 1024 * 1024;
	public final static String STORE_NAME_FORMAT = "store.warc.%s.%s.gz";
	public final static String DIGESTS_NAME = "digests.bloom";
	public final static int NUM_GZ_WARC_RECORDS = 16;
	private int maxRecordsPerFile = 25600;
	private int maxSecondsBetweenDumps = 600
	private int currentNumberOfRecordsInFile = 0;
	private Date lastDumpTime = new Date();
	private Object counterLock = new Object();
	private FastBufferedOutputStream outputStream;	
	private ParallelBufferedWarcWriter writer;

	private final File storeDir;

	public MultiWarcStore( final RuntimeConfiguration rc ) throws IOException {
		storeDir = rc.storeDir;
		maxRecordsPerFile = rc.maxRecordsPerFile;
		maxSecondsBetweenDumps = rc.maxSecondsBetweenDumps;
		createNewWriter( );	
	}
	private String generateStoreName() {
		Date dNow = new Date();
	        SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss.SSS");
        	String datetime = ft.format(dNow);
		return String.format(STORE_NAME_FORMAT, datetime, UUID.randomUUID());
	}

	private void createNewWriter() throws IOException {
		final File file = new File( storeDir, generateStoreName() );
                outputStream = new FastBufferedOutputStream( new FileOutputStream( file ), OUTPUT_STREAM_BUFFER_SIZE );
		writer = new ParallelBufferedWarcWriter( outputStream, true );
	}
		
	@Override
	public void store( final URI uri, final HttpResponse response, final boolean isDuplicate, final byte[] contentDigest, final String guessedCharset ) throws IOException, InterruptedException {
		
		if ( contentDigest == null ) throw new NullPointerException( "Content digest is null" );
		final HttpResponseWarcRecord record = new HttpResponseWarcRecord( uri, response );
		HeaderGroup warcHeaders = record.getWarcHeaders();
		warcHeaders.updateHeader( new WarcHeader( WarcHeader.Name.WARC_PAYLOAD_DIGEST, "bubing:" + Hex.encodeHexString( contentDigest ) ) );
		if ( guessedCharset != null ) warcHeaders.updateHeader( new WarcHeader( WarcHeader.Name.BUBING_GUESSED_CHARSET, guessedCharset ) );
		if ( isDuplicate ) warcHeaders.updateHeader( new WarcHeader( WarcHeader.Name.BUBING_IS_DUPLICATE, "true" ) );
		synchronized(counterLock) {
			currentNumberOfRecordsInFile += 1;
			Date currentTime = new Date();
			if ((currentNumberOfRecordsInFile > targetRecordsPerFile) || ((currentTime.getTime()-lastDumpTime.getTime()/1000 > maxSecondsBetweenDumps)) {
				currentNumberOfRecordsInFile = 0;
				lastDumpTime = currentTime;
				LOGGER.warn( "Target number of records reached, creating new output file" );
				try {
					writer.close();
				} catch ( IOException badshitHappens ) {
					LOGGER.error( "Closing interrupted");
				}
				outputStream.close();
				createNewWriter();
			}
		}
		writer.write( record );
	}
	
	@Override
	public synchronized void close() throws IOException {
		try {
			writer.close();
		}
		catch ( IOException shouldntHappen ) {
			LOGGER.error( "Interrupted while closing parallel output stream" );
		}
		outputStream.close();
	}
}
