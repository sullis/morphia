package dev.morphia;

import com.antwerkz.bottlerocket.BottleRocket;
import com.antwerkz.bottlerocket.clusters.MongoCluster;
import com.antwerkz.bottlerocket.clusters.ReplicaSet;
import com.github.zafarkhaja.semver.Version;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoClientSettings.Builder;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import dev.morphia.mapping.MappedClass;
import dev.morphia.mapping.Mapper;
import dev.morphia.mapping.MapperOptions;
import dev.morphia.query.DefaultQueryFactory;
import org.apache.commons.io.FileUtils;
import org.bson.Document;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;

import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

@SuppressWarnings("WeakerAccess")
public abstract class TestBase {
    protected static final String TEST_DB_NAME = "morphia_test";
    private static final Logger LOG = LoggerFactory.getLogger(TestBase.class);
    private static final MapperOptions mapperOptions = MapperOptions.DEFAULT;
    private static MongoClient mongoClient;

    private final MongoDatabase database;
    private final Datastore ds;

    protected TestBase() {
        this.database = getMongoClient().getDatabase(TEST_DB_NAME);
        this.ds = Morphia.createDatastore(getMongoClient(), database.getName());
        ds.setQueryFactory(new DefaultQueryFactory());
    }

    static void startMongo() {
        Builder builder = MongoClientSettings.builder();

        try {
            builder.uuidRepresentation(mapperOptions.getUuidRepresentation());
        } catch (Exception ignored) {
            // not a 4.0 driver
        }

        String mongodb = System.getenv("MONGODB");
        File mongodbRoot = new File("target/mongo");
        int port = new Random().nextInt(20000) + 30000;
        try {
            FileUtils.deleteDirectory(mongodbRoot);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        Version version = mongodb != null ? Version.valueOf(mongodb) : BottleRocket.DEFAULT_VERSION;
        final MongoCluster cluster = ReplicaSet.builder()
                                               .version(mongodb != null ? Version.valueOf(mongodb) : BottleRocket.DEFAULT_VERSION)
                                               .baseDir(mongodbRoot)
                                               .port(port)
                                               .size(version.lessThan(Version.forIntegers(4)) ? 3 : 1)
                                               .build();

        cluster.start();
        mongoClient = cluster.getClient(builder);
    }

    public MongoDatabase getDatabase() {
        return database;
    }

    public Datastore getDs() {
        return ds;
    }

    public Mapper getMapper() {
        return getDs().getMapper();
    }

    public MongoClient getMongoClient() {
        if (mongoClient == null) {
            startMongo();
        }
        return mongoClient;
    }

    public boolean isReplicaSet() {
        return runIsMaster().get("setName") != null;
    }

    @Before
    public void setUp() {
        cleanup();
        installSampleData();
    }

    @After
    public void tearDown() {
        cleanup();
    }

    protected void assertDocumentEquals(final Object expected, final Object actual) {
        assertDocumentEquals("", expected, actual);
    }

    protected void checkMinServerVersion(final double version) {
        assumeTrue(serverIsAtLeastVersion(version));
    }

    protected void cleanup() {
        MongoDatabase db = getDatabase();
        db.listCollectionNames().forEach(s -> {
            if (!s.equals("zipcodes") && !s.startsWith("system.")) {
                db.getCollection(s).drop();
            }
        });
    }

    protected int count(final MongoCursor<?> cursor) {
        int count = 0;
        while (cursor.hasNext()) {
            cursor.next();
            count++;
        }
        return count;
    }

    protected int count(final Iterator<?> iterator) {
        int count = 0;
        while (iterator.hasNext()) {
            count++;
            iterator.next();
        }
        return count;
    }

    protected MongoCollection<Document> getDocumentCollection(final Class<?> type) {
        return getDatabase().getCollection(getMappedClass(type).getCollectionName());
    }

    protected List<Document> getIndexInfo(final Class<?> clazz) {
        return getMapper().getCollection(clazz).listIndexes().into(new ArrayList<>());
    }

    protected MappedClass getMappedClass(final Class<?> aClass) {
        Mapper mapper = getMapper();
        mapper.map(aClass);

        return mapper.getMappedClass(aClass);
    }

    protected double getServerVersion() {
        String version = (String) getMongoClient()
                                      .getDatabase("admin")
                                      .runCommand(new Document("serverStatus", 1))
                                      .get("version");
        return Double.parseDouble(version.substring(0, 3));
    }

    /**
     * @param version must be a major version, e.g. 1.8, 2,0, 2.2
     * @return true if server is at least specified version
     */
    protected boolean serverIsAtLeastVersion(final double version) {
        return getServerVersion() >= version;
    }

    protected String toString(final Document document) {
        return document.toJson(getMapper().getCodecRegistry().get(Document.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertDocumentEquals(final String path, final Object expected, final Object actual) {
        assertSameNullity(path, expected, actual);
        if (expected == null) {
            return;
        }
        assertSameType(path, expected, actual);

        if (expected instanceof Document) {
            for (final Entry<String, Object> entry : ((Document) expected).entrySet()) {
                final String key = entry.getKey();
                Object expectedValue = entry.getValue();
                Object actualValue = ((Document) actual).get(key);
                assertDocumentEquals(path + "." + key, expectedValue, actualValue);
            }
        } else if (expected instanceof List) {
            List list = (List) expected;
            List copy = new ArrayList<>((List) actual);

            Object o;
            for (int i = 0; i < list.size(); i++) {
                o = list.get(i);
                boolean found = false;
                final Iterator other = copy.iterator();
                while (!found && other.hasNext()) {
                    try {
                        String newPath = format("%s[%d]", path, i);
                        assertDocumentEquals(newPath, o, other.next());
                        other.remove();
                        found = true;
                    } catch (AssertionError ignore) {
                    }
                }
                if (!found) {
                    fail(format("mismatch found at %s", path));
                }
            }
        } else {
            assertEquals(format("mismatch found at %s:%n%s", path, expected, actual), expected, actual);
        }
    }

    private void assertSameNullity(final String path, final Object expected, final Object actual) {
        if (expected == null && actual != null
            || actual == null && expected != null) {
            assertEquals(format("mismatch found at %s:%n%s", path, expected, actual), expected, actual);
        }
    }

    private void assertSameType(final String path, final Object expected, final Object actual) {
        if (expected instanceof List && actual instanceof List) {
            return;
        }
        if (!expected.getClass().equals(actual.getClass())) {
            assertEquals(format("mismatch found at %s:%n%s", path, expected, actual), expected, actual);
        }
    }

    private void download(final URL url, final File file) throws IOException {
        LOG.info("Downloading zip data set to " + file);
        try (InputStream inputStream = url.openStream(); FileOutputStream outputStream = new FileOutputStream(file)) {
            byte[] read = new byte[49152];
            int count;
            while ((count = inputStream.read(read)) != -1) {
                outputStream.write(read, 0, count);
            }
        }
    }

    private void installSampleData() {
        File file = new File("zips.json");
        try {
            if (!file.exists()) {
                file = new File("target/zips.json");
                if (!file.exists()) {
                    download(new URL("https://media.mongodb.org/zips.json"), file);
                }
            }
            MongoCollection<Document> zips = getDatabase().getCollection("zipcodes");
            if (zips.countDocuments() == 0) {
                LOG.info("Installing sample data");
                MongoCollection<Document> zipcodes = getDatabase().getCollection("zipcodes");
                Files.lines(file.toPath())
                     .forEach(l -> zipcodes.insertOne(Document.parse(l)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        assumeTrue("Failed to process media files", file.exists());
    }

    private Document runIsMaster() {
        return mongoClient.getDatabase("admin")
                          .runCommand(new Document("ismaster", 1));
    }
}
