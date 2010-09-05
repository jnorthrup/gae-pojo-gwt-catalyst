package inc.glamdring;

import com.google.appengine.api.datastore.*;

import javax.persistence.Id;
import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class Catalyst {

    public static DatastoreService DS;
    public static Map<Class, String> classKindMap = new HashMap<Class, String>();
    public static Map<String, Class> kindMap = new HashMap<String, Class>();
    public static Map<Class, Field> oidMap = new HashMap<Class, Field>();
    public static Map<Class, Field> idMap = new HashMap<Class, Field>();
    public static Map<Type, Map<String, Field>> fieldsWhichMustBeSerialized = new HashMap<Type, Map<String, Field>>();

    /**
     * static init function to initialize things.
     *
     * @param pkg a java package.
     * @param ds a datastore, if you'd like to swap the default datastore out for a specific one.  otherwise, one will be provided for the life of the servlet.
     */
    public static void init(Package pkg, DatastoreService... ds) {

        DS = ds.length > 0 ? ds[0] : DS == null ? DatastoreServiceFactory.getDatastoreService() : DS;
        try {
            getMetaForPackage(pkg);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();  /*Todo: verify for a purpose*/
        }
    }

    public static <E> Query mapQuery(Class<E> aClass) {
        return new Query(inferKind(aClass));
    }

    public static <P, C> String putChild(C childPojo, P parentPojo, Class<C>... childClass) {
        final KeyRange range = DS.allocateIds(inferKey(parentPojo), inferKind(childClass.length > 0 ? childClass[0] : childPojo.getClass()), 1);
        String ret = null;
        for (Key key : range) {
            ret = KeyFactory.keyToString(DS.put(marshal(childPojo, new Entity(key))));
        }
        return ret;
    }

    public static <E> String inferKind(Class<E> aClass) {
        String s;
        if (aClass.isAnnotationPresent(javax.persistence.Entity.class))
            s = aClass.getAnnotation(javax.persistence.Entity.class).name();
        else
            s = aClass.getName().replace(".", "_");
        return s;
    }

    public static <E> E[] getQuery(Class<E> aClass, Query query) {
        final PreparedQuery preparedQuery = DS.prepare(query);
        final Iterator<Entity> entityIterator = preparedQuery.asIterator();
        return Catalyst.get(aClass, entityIterator);
    }

    public static <E> E[] get(Class<E> aClass, Iterator<Entity> entityIterator) {
        final ArrayList<E> arrayList = new ArrayList<E>();
        while (entityIterator.hasNext()) {
            Entity entity = entityIterator.next();
            try {
                final E bean = populate(aClass, entity);
                arrayList.add(bean);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return arrayList.toArray((E[]) Array.newInstance(aClass, arrayList.size()));
    }

    public static <E> E populate(Class<E> aClass, Entity entity) throws InstantiationException, IllegalAccessException {
        final Map<String, Object> map = (entity).getProperties();
        final E bean = aClass.newInstance();

        populate(map, bean, entity.getKey());
        return bean;
    }

    /**
     * @param map  map->pojo
     * @param pojo a pojo with accessible fields
     * @param key  DS key
     * @param <E>
     * @throws IllegalAccessException
     */
    public static <E> void populate(Map<String, Object> map, E pojo, Key key) throws IllegalAccessException {
        Class<E> aClass = (Class<E>) pojo.getClass();

        //noinspection AppEngineForbiddenCode
        final Set<String> keys = new HashSet<String>(map.keySet());
        Field oidField = null;
        if (null != key) {
            if (oidMap.containsKey(aClass)) {
                oidField = oidMap.get(aClass);
                oidField.set(pojo, KeyFactory.keyToString(key));
            }
            Field idField = null;
            if (idMap.containsKey(aClass)) {
                idField = idMap.get(aClass);
                final String value = key.getName();
                idField.set(pojo, value == null ? key.getId() : value);
            }

            keys.removeAll(Arrays.asList(oidField != null ? oidField.getName() : "", idField != null ? idField.getName() : ""));
        }
        for (String fname : keys) {
            final Field field;
            if (map.containsKey(fname)) {
                try {
                    field = aClass.getDeclaredField(fname);
                    field.set(pojo, map.get(fname));
                } catch (Exception e) {

                }
            }
        }
    }

    /**
     * dead simple map->pojo populate method
     *
     * @param pojo newInstance of <V>
     * @param map  a map
     * @param <V>  a pojo
     */
    public static <V> void populate(V pojo, Map<String, Object> map) {
        final Class<V> aClass = (Class<V>) pojo.getClass();
        for (String fname : map.keySet()) {
            final Field field;
            if (map.containsKey(fname)) {
                try {
                    field = aClass.getDeclaredField(fname);
                    field.set(pojo, map.get(fname));
                } catch (Exception e) {

                }
            }
        }

    }

    public static <V> V get(Key key) throws EntityNotFoundException {
        try {
            final Map<String, Object> map = unmarshal(key);
            final V bean = (V) of(key.getKind());

            populate(bean, map);
            if (oidMap.containsKey(bean.getClass()))
                oidMap.get(bean.getClass()).set(bean, KeyFactory.keyToString(key));
            if (idMap.containsKey(bean.getClass())) {
                final Serializable value = key.getName() == null ? key.getId() : key.getName();
                idMap.get(bean.getClass()).set(bean, value);
            }

        } catch (InstantiationException e) {
            e.printStackTrace();  /*Todo: verify for a purpose*/
        } catch (IllegalAccessException e) {
            e.printStackTrace();  /*Todo: verify for a purpose*/
        }
        throw new RuntimeException("Error failed during ORM processing");
    }

    /**
     * provides a key inferred from contents of Entity.   if entity has
     *
     * @param e   entity instance
     * @param <E> entity type
     * @return a DS key
     * @Id annotation on a field, that is a top-level key created from the value,
     * otherwise an @Oid will be used (and is preferred) with a complete key encoded.
     */
    public static <E> Key inferKey(E e) {
        final Class<E> aClass = (Class<E>) e.getClass();
        Key key = null;
        if (oidMap.containsKey(aClass)) {
            try {
                String oid = (String) (oidMap.get(aClass)).get(e);
                key = KeyFactory.stringToKey(oid);
            } catch (IllegalAccessException e1) {
                e1.printStackTrace();  /*Todo: verify for a purpose*/
            }
        } else if (idMap.containsKey(aClass)) {
            Object id = null;
            try {
                id = idMap.get(aClass).get(e);
            } catch (IllegalAccessException e1) {
                e1.printStackTrace();  /*Todo: verify for a purpose*/
            }
            if (id instanceof String) {
                String s = (String) id;
                key = KeyFactory.createKey(inferKind(aClass), s);

            } else {
                key = KeyFactory.createKey(inferKind(aClass), (Long) id);
            }
        }

        return key;
    }


    public static <V> Entity marshal(V e, Entity... optional) {
        Map<Field, Object> bm = new HashMap<Field, Object>(3);

        for (Field field : e.getClass().getDeclaredFields()) {
            try {
                bm.put(field, field.get(e));
            } catch (Exception e1) {
            }
        }
        try {
            final Field oid;
            Field id = null;
            Entity entity = null;
            oid = oidMap.get(e.getClass());
            if (oid == null) id = idMap.get(e.getClass());
            if (0 != optional.length) {
                entity = optional[0];
            } else {
                if (null != oid) {
                    final String oidString = oid.get(e).toString();
                    final Key key1 = KeyFactory.stringToKey(oidString);
                    entity = new Entity(key1);
                } else {
                    final String inferredKind = inferKind(e.getClass());
                    if (null != id) {

                        final Object o = id.get(e);

                        final Key key;
                        if (o instanceof String) {
                            String s = (String) o;
                            key = KeyFactory.createKey(inferredKind, s);
                            entity = new Entity(key);
                        } else if (o instanceof Long) {
                            Long aLong = (Long) o;
                            key = KeyFactory.createKey(inferredKind, aLong);
                            entity = new Entity(key);
                        } else
                            entity = new Entity(inferredKind);

                    } else entity = new Entity(inferredKind);
                }

            }

            final Set<Field> set = bm.keySet();
            for (Field key : set) {
                if (key.getName().intern() != "class".intern()) {
                    Object value = bm.get(key);
                    if (!key.equals(oid.getName()) && null != value) {
                        final Class<? extends Object> valueClass = value.getClass();
                        if (classKindMap.containsKey(valueClass)) {
                            value = marshal(e);
                        } else if (value instanceof String) {
                            String s = (String) value;
                            if (s.length() > 500) value = new Text(s);
                        } else if (fieldsWhichMustBeSerialized.containsKey(valueClass) && fieldsWhichMustBeSerialized.get(valueClass).containsKey(key)) {
                            final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                            final ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
                            objectOutputStream.writeObject(value);
                            value = byteArrayOutputStream.toByteArray();
                        }
                        if (value instanceof byte[]) {
                            byte[] bytes = (byte[]) value;
                            value = new Blob(bytes);
                        }

                        entity.setProperty(key.getName(), value);
                    }
                }

            }
            return entity;
        } catch (Exception e1) {
            e1.printStackTrace();  /*Todo: verify for a purpose*/
        }
        throw new RuntimeException("fialure unmarshallling " + e.toString());
    }

    public static Map<String, Object> translate(Entity entity) throws EntityNotFoundException {
        final Map<String, Object> map = entity.getProperties();
        final Map<String, Field> fieldSet = fieldsWhichMustBeSerialized.get(as(entity.getKind()));

        //noinspection AppEngineForbiddenCode
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            final Object value = entry.getValue();
            if (value instanceof Key) {
                Key key1 = (Key) value;
                entry.setValue(get(key1));
            } else if (value instanceof Blob) {
                Blob blob = (Blob) value;
                final byte[] bytes = blob.getBytes();

                if (null != fieldSet && fieldSet.containsKey(entry.getKey())) {
                    try {
                        final ObjectInputStream inputStream = new ObjectInputStream(new ByteArrayInputStream(bytes));
                        final Object o = inputStream.readObject();
                        entry.setValue(o);
                    } catch (IOException e) {
                        entry.setValue(null);
                    } catch (ClassNotFoundException e) {
                        e.printStackTrace();  /*Todo: verify for a purpose*/
                    }
                } else
                    entry.setValue(bytes);

            } else if (value instanceof Text) {
                Text text = (Text) value;
                entry.setValue(text.getValue());
            }
        }
        return map;
    }

    public static <V> Key put(V e) {
        return DS.put(marshal(e));
    }

    public static Map<String, Object> unmarshal(Key key) throws EntityNotFoundException {
        final Entity entity = DS.get(key);
        return translate(entity);
    }

    public static <V> V of(final String s) throws InstantiationException, IllegalAccessException {
        return Catalyst.<V>as(s).newInstance();
    }

    public static <V> Class<V> as(final String s) {
        return (Class<V>) kindMap.get(s);
    }

    public static void getMetaForPackage(Package package_)
            throws ClassNotFoundException {
        // This will hold a list of directories matching the pckgname.
        //There may be more than one if a package is split over multiple jars/paths
        List<Class> classes = new ArrayList<Class>();
        ArrayList<File> directories = new ArrayList<File>();
        String pckgname = package_.getName();
        try {

            ClassLoader cld = Thread.currentThread().getContextClassLoader();
            if (cld == null) {
                throw new ClassNotFoundException("Can't get class loader.");
            }
            // Ask for all resources for the path
            final String resName = pckgname.replace('.', '/');
            Enumeration<URL> resources = cld.getResources(resName);
            while (resources.hasMoreElements()) {
                URL res = resources.nextElement();
                if (res.getProtocol().equalsIgnoreCase("jar") || res.getProtocol().equalsIgnoreCase("zip")) {
                    JarURLConnection conn = (JarURLConnection) res.openConnection();
                    JarFile jar = conn.getJarFile();

                    for (JarEntry e : Collections.list(jar.entries())) {
                        if (e.getName().startsWith(resName) && e.getName().endsWith(".class") && !e.getName().contains("$")) {
                            String className = e.getName().replace("/", ".").substring(0, e.getName().length() - 6);
                            System.out.println(className);
                            classes.add(Class.forName(className));
                        }
                    }
                } else
                    directories.add(new File(URLDecoder.decode(res.getPath(), "UTF-8")));
            }
        } catch (NullPointerException x) {
            throw new ClassNotFoundException(pckgname + " does not appear to be " +
                    "a valid package (Null pointer exception)");
        } catch (UnsupportedEncodingException encex) {
            throw new ClassNotFoundException(pckgname + " does not appear to be " +
                    "a valid package (Unsupported encoding)");
        } catch (IOException ioex) {
            throw new ClassNotFoundException("IOException was thrown when trying " +
                    "to get all resources for " + pckgname);
        }

        // For every directory identified capture all the .class files
        for (File directory : directories) {
            if (directory.exists()) {
                // Get the list of the files contained in the package
                String[] files = directory.list();
                for (String file : files) {
                    // we are only interested in .class files
                    if (file.endsWith(".class")) {
                        // removes the .class extension
                        classes.add(Class.forName(pckgname + '.' + file.substring(0, file.length() - 6)));
                    }
                }
            } else {
                throw new ClassNotFoundException(pckgname + " (" + directory.getPath() +
                        ") does not appear to be a valid package");
            }
        }

        for (Class aClass : classes) {
            if (!aClass.isAnnotationPresent(javax.persistence.Entity.class)) {
                continue;
            }
            final String kind = ((javax.persistence.Entity) aClass.getAnnotation(javax.persistence.Entity.class)).name();
            classKindMap.put(aClass, kind);
            kindMap.put(kind, aClass);

        }
        for (Class aClass : classKindMap.keySet()) {


            final Field[] declaredFields = aClass.getDeclaredFields();
            for (Field declaredField : declaredFields) {
                if (declaredField.isAnnotationPresent(Oid.class)) {
                    oidMap.put(aClass, declaredField);
                }
                if (declaredField.isAnnotationPresent(Id.class)) {
                    idMap.put(aClass, declaredField);
                }
                final Class type = declaredField.getType();
                if (!classKindMap.containsKey(type) && !DataTypeUtils.isSupportedType(type)) {
                    HashMap<String, Field> value;
                    if (!fieldsWhichMustBeSerialized.containsKey(aClass)) {
                        value = new HashMap<String, Field>();
                        fieldsWhichMustBeSerialized.put(aClass, value);
                    } else
                        value = (HashMap<String, Field>) fieldsWhichMustBeSerialized.get(aClass);
                    value.put(declaredField.getName(), declaredField);
                }
            }
        }
    }
}