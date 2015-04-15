package org.intermine.dataloader;

/*
 * Copyright (C) 2002-2015 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.intermine.metadata.ClassDescriptor;
import org.intermine.metadata.FieldDescriptor;
import org.intermine.metadata.Model;
import org.intermine.metadata.PrimaryKey;
import org.intermine.metadata.ReferenceDescriptor;
import org.intermine.metadata.Util;
import org.intermine.model.FastPathObject;
import org.intermine.model.InterMineObject;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.proxy.ProxyReference;
import org.intermine.util.CollectionUtil;
import org.intermine.util.DynamicUtil;

/**
 * A DataLoader with helper methods for creating and storing objects using an IntegrationWriter.
 *
 * @author Kim Rutherford
 */

public class DirectDataLoader extends DataLoader
{
    private static final Logger LOG = Logger.getLogger(DirectDataLoader.class);
    private int idCounter = 0;
    private int storeCount = 0;
    private long startTime;
    private long stepTime;
    private String sourceName;
    private String sourceType;
    private List<FastPathObject> buffer = new ArrayList<FastPathObject>();

    private static final int LOG_FREQUENCY = 100000;
    private static final int COMMIT_FREQUENCY = 500000;

    /**
     * Create a new DirectDataLoader using the given IntegrationWriter and source name.
     * @param iw an IntegrationWriter
     * @param sourceName the source name
     * @param sourceType the source type
     */
    public DirectDataLoader(IntegrationWriter iw, String sourceName, String sourceType) {
        super(iw);
        this.sourceName = sourceName;
        this.sourceType = sourceType;
        this.startTime = System.currentTimeMillis();
        this.stepTime = startTime;
    }

    /**
     * Store an object using the IntegrationWriter.
     * @param o the InterMineObject
     * @throws ObjectStoreException if there is a problem in the IntegrationWriter
     */
    public void store(FastPathObject o) throws ObjectStoreException {

        buffer.add(o);


        if (buffer.size() == 50) {
            storeBatch();
        }
    }

    private void storeBatch() throws ObjectStoreException {
        Source source = getIntegrationWriter().getMainSource(sourceName, sourceType);
        Source skelSource = getIntegrationWriter().getSkeletonSource(sourceName, sourceType);

        // first get equivalent objects for buffered objects
        if (getIntegrationWriter() instanceof IntegrationWriterDataTrackingImpl) {
            checkForProxiesInPrimaryKeys(source);

            HintingFetcher eof =
                    ((IntegrationWriterDataTrackingImpl) getIntegrationWriter()).getEof();
            if (eof instanceof BatchingFetcher) {
                ((BatchingFetcher) eof).getEquivalentsForObjects(buffer);
            } else {
                LOG.warn("Not a batching fetcher, was: " + eof.getClass());
            }
        }

        LOG.info("Storing batch of " + buffer.size() + " objects.");
        // now store, the equivalent objects should be in cache
        for (FastPathObject o : buffer) {
            getIntegrationWriter().store(o, source, skelSource);
            storeCount++;
            if (storeCount % LOG_FREQUENCY == 0) {
                long now = System.currentTimeMillis();
                LOG.info("Dataloaded " + storeCount + " objects - running at "
                        + ((60000L * LOG_FREQUENCY) / (now - stepTime)) + " (avg "
                        + ((60000L * storeCount) / (now - startTime))
                        + ") objects per minute -- now on "
                        + Util.getFriendlyName(o.getClass()));
                stepTime = now;
            }
            if (storeCount % COMMIT_FREQUENCY == 0) {
                LOG.info("Committing transaction after storing " + storeCount + " objects.");
                getIntegrationWriter().batchCommitTransaction();
            }
        }
        // now clear ready for next objects
        buffer.clear();
    }

    private void checkForProxiesInPrimaryKeys(Source source) {
        Model model = getIntegrationWriter().getModel();

        // TODO we can skip a lot of this by first finding keys for the source and checking for
        // any keys that contain a reference. Then we only need to see if we have any objects of
        // those classes in the buffer.

        // create list that contains only InterMineObjects (remove simple objects)
        List<InterMineObject> imos = new ArrayList<InterMineObject>();
        for (FastPathObject fpo : buffer) {
            if (fpo instanceof InterMineObject) {
                imos.add((InterMineObject) fpo);
            }
        }

        // group the current objects by class
        Map<Class<?>, List<InterMineObject>> objsByClass = CollectionUtil.groupByClass(imos,
                false);

        System.out.println("objsByClass.keySet(): " + objsByClass.keySet());
        HashSet<ClassDescriptor> cldsDone = new HashSet<ClassDescriptor>();

        Map<Class<?>, Set<String>> refsInKeys = new HashMap<Class<?>, Set<String>>();
        // find any primary keys that include a reference
        for (Class<?> c : objsByClass.keySet()) {
            Set<ClassDescriptor> classDescriptors = model.getClassDescriptorsForClass(c);
            for (ClassDescriptor cld : classDescriptors) {
                if (!cldsDone.contains(cld)) {
                    cldsDone.add(cld);
                    Set<PrimaryKey> keysForClass = DataLoaderHelper.getPrimaryKeys(cld, source,
                            getIntegrationWriter().getObjectStore());

                    for (PrimaryKey pk : keysForClass) {
                        for (String fieldName: pk.getFieldNames()) {
                            FieldDescriptor fld = cld.getFieldDescriptorByName(fieldName);
                            if (fld instanceof ReferenceDescriptor) {
                                System.out.println("Key has a reference: " + pk.getName() + " "
                                        + cld.getName() + " " + fld.getName());
                                LOG.info("Key has a reference: " + pk.getName() + " "
                                        + cld.getName() + " " + fld.getName());
                                Set<String> refFieldNames = refsInKeys.get(cld.getType());
                                if (refFieldNames == null) {
                                    refFieldNames = new HashSet<String>();
                                    refsInKeys.put(cld.getType(), refFieldNames);
                                }
                                refFieldNames.add(fld.getName());
                            }
                        }
                    }
                }
            }
        }

        System.out.println("refsInKeys: " + refsInKeys);

        // for each class in batch see if we need to check for ProxyReferences
        for (Class<?> keyC : refsInKeys.keySet()) {
            for (Class<?> objC : objsByClass.keySet()) {
                System.out.println("keyC: " + keyC + " objC " + objC
                        + " DynamicUtil.isassignableFrom(keyC, objC): " + DynamicUtil.isAssignableFrom(keyC,  objC));

                if (DynamicUtil.isAssignableFrom(keyC,  objC)) {
                    for (String fieldName : refsInKeys.get(keyC)) {
                        // for each object being stored of this type make sure the referenced object
                        // isn't a proxyRefernece
                        for (InterMineObject obj : objsByClass.get(objC)) {
                            try {
                                InterMineObject refObj = (InterMineObject) obj.getFieldProxy(fieldName);
                                System.out.println(DynamicUtil.getSimpleClassName(obj) + "." + fieldName
                                        + " - " + refObj.getClass());
                                if (refObj instanceof ProxyReference) {
                                    throw new IllegalArgumentException("Found ProxyReference in a key field reference");
                                }
                            } catch (IllegalAccessException e) {
                                System.out.println("IllegalAccessException");
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Close the DirectDataLoader, this just prints a final log message with loading stats.
     */
    public void close() throws ObjectStoreException {
        // make sure we store any remaining objects
        storeBatch();
        long now = System.currentTimeMillis();
        LOG.info("Finished dataloading " + storeCount + " objects at " + ((60000L * storeCount)
                / (now - startTime)) + " objects per minute (" + (now - startTime)
            + " ms total) for source " + sourceName);
    }
    /**
     * Create a new object of the given class name and give it a unique ID.
     * @param className the class name
     * @return the new InterMineObject
     * @throws ClassNotFoundException if the given class doesn't exist
     */
    @SuppressWarnings("unchecked")
    public InterMineObject createObject(String className) throws ClassNotFoundException {
        return createObject((Class<? extends InterMineObject>) Class.forName(className));
    }

    /**
     * Create a new object of the given class and give it a unique ID.
     * @param c the class
     * @param <C> the type of the class
     * @return the new InterMineObject
     */
    public <C extends InterMineObject> C createObject(Class<C> c) {
        C o = DynamicUtil.simpleCreateObject(c);
        o.setId(new Integer(idCounter));
        idCounter++;
        return o;
    }

    /**
     * Create a 'simple object' which doesn't inherit from InterMineObject and doesn't have an id.
     * @param c the class of object to create
     * @param <C> the type of the class
     * @return an empty simple object of the given class
     */
    public <C extends FastPathObject> C createSimpleObject(Class<C> c) {
        C o = DynamicUtil.simpleCreateObject(c);
        return o;
    }

}
