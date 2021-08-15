/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2018 Delta Air Lines, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.osgi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;


/**
 * <p>
 * Utility class to monitor a directory for changes.
 * </p>
 *
 * @author Tony Cook
 */
public class DirectoryWatcher implements Runnable {
    protected static final Logger LOGGER = LoggerFactory.getLogger(DirectoryWatcher.class);

    List<Consumer<DirectoryEvent>> listeners = new ArrayList<>();
    WatchService watcher;
    Path path;

    public DirectoryWatcher(Path path, Kind<?>... eventKinds) throws IOException {
        watcher = path.getFileSystem().newWatchService();
        path.register(watcher, eventKinds);
        this.path = path;
    }

    public boolean addListener(Consumer<DirectoryEvent> f) {
        return listeners.add(f);
    }

    public boolean removeListener(Consumer<DirectoryEvent> f) {
        return listeners.remove(f);
    }

    @Override
    public void run() {
        Thread.currentThread().setName("DirWatcher");
        WatchKey watchKey = null;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                try {
                    watchKey = watcher.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    continue;
                }

                List<WatchEvent<?>> events = watchKey.pollEvents();
                for (WatchEvent<?> event : events) {
                    WatchEvent.Kind<?> kind = event.kind();

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path filename = ev.context();

                    DirectoryEvent directoryEvent = new DirectoryEvent();
                    directoryEvent.path = Paths.get(path.toString(), filename.toString()).toAbsolutePath();
                    directoryEvent.fileName = filename.toString();
                    directoryEvent.kind = kind;
//				    if (kind == StandardWatchEventKinds.ENTRY_CREATE ) {
                    for (var l : listeners) {
                        l.accept(directoryEvent);
                    }
//				    }			
                }
            } catch (Throwable e) {
                LOGGER.error("Error while processing watch events");
                e.printStackTrace();
            } finally {
                if (watchKey != null)
                    watchKey.reset();
            }
        }
    }
}
