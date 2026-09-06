package com.ramussoft.core.format;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ramussoft.common.Attribute;
import com.ramussoft.common.AttributeType;
import com.ramussoft.common.Element;
import com.ramussoft.common.Engine;
import com.ramussoft.common.PluginFactory;
import com.ramussoft.common.Qualifier;
import com.ramussoft.common.attribute.AttributePlugin;
import com.ramussoft.common.persistent.Persistent;
import com.ramussoft.core.format.yaml.YamlFormat;

/**
 * Читає модель із дерева YAML-файлів у порожній {@link Engine}.
 * <p>
 * Симетричний до {@link ProjectWriter}. Числові ключі не зберігаються у файлах
 * — вони відновлюються з ідентифікаторів через {@link StableIds}, тому
 * {@code експорт → імпорт → експорт} дає ті самі файли.
 * <p>
 * Свіжий рушій уже містить системні класифікатори й атрибути, тому сутність із
 * наявним ключем не створюється повторно, а узгоджується.
 */
public class ProjectReader {

    private static final String QUALIFIER = "qualifier";

    private static final String ATTRIBUTE = "attribute";

    private static final String ELEMENT = "element";

    private final Engine engine;

    private final PersistentCodec codec;

    private final PluginFactory factory;

    /**
     * Системні атрибути поточного рушія за іменем. Заповнюється лениво, бо на
     * момент створення читача плагіни ще могли не додати всі свої.
     */
    private Map<String, Attribute> systemAttributes;

    private final StableIdRegistry registry;

    /**
     * Властивості атрибутів, відкладені до створення класифікаторів: вони на
     * них посилаються.
     */
    private final Map<String, Object> deferredProperties =
            new java.util.LinkedHashMap<String, Object>();

    /**
     * Елементи, уже зіставлені з файлом: один елемент не має відповідати двом
     * записам.
     */
    private final Set<Long> bound = new HashSet<Long>();

    public ProjectReader(Engine engine) {
        this.engine = engine;
        this.registry = new StableIdRegistry(engine);
        this.codec = new PersistentCodec(registry, engine);
        this.factory = (PluginFactory) engine.getPluginProperty("Core",
                "PluginFactory");
        if (factory == null)
            throw new IllegalStateException(
                    "Рушій не має властивості Core/PluginFactory:"
                            + " без переліку плагінів значення атрибутів"
                            + " не відновити");
    }

    public void read(File directory) throws IOException {
        Map<String, Object> project = readDocument(new File(directory,
                "project.yaml"));
        checkSchema(project);

        // Плагіни добудовують модель у відповідь на зміни: створюють базові
        // функції для нових класифікаторів, переписують ієрархію тощо. Під час
        // імпорту це шкодить — дані вже є у файлі, і автостворення дає
        // дублікати. Вимикаємо на час читання.
        boolean autoupdate = setAutoupdateDisabled(true);
        try {
            readInto(directory);
        } finally {
            setAutoupdateDisabled(autoupdate);
        }
    }

    /**
     * Вмикає чи вимикає автодобудову моделі плагінами.
     * <p>
     * Прапорець живе в {@code StandardAttributesPlugin}, а той — у модулі
     * {@code core-simple-attributes}, який сам залежить від {@code core}.
     * Пряме звернення утворило б цикл залежностей, тому тут рефлексія: одна
     * локальна поступка замість інверсії шарів.
     *
     * @return попереднє значення прапорця
     */
    private boolean setAutoupdateDisabled(boolean disabled) {
        Object plugin = engine.getPluginProperty("Core",
                "StandardAttributesPlugin");
        if (plugin == null)
            return false;
        try {
            java.lang.reflect.Field field = plugin.getClass()
                    .getDeclaredField("disableAutoupdate");
            field.setAccessible(true);
            boolean previous = field.getBoolean(plugin);
            field.setBoolean(plugin, disabled);
            return previous;
        } catch (Exception e) {
            // Немає прапорця — просто працюємо з увімкненою автодобудовою.
            return false;
        }
    }

    private void readInto(File directory) throws IOException {

        readAttributes(new File(directory, "attributes.yaml"));

        File qualifiers = new File(directory, "qualifiers");
        File[] files = qualifiers.listFiles();
        if (files == null)
            return;
        // Порядок читання не має впливати на результат, але сортуємо, щоб
        // повідомлення про помилку були відтворюваними.
        Arrays.sort(files);

        // Два проходи. Значення атрибутів можуть посилатися на елементи інших
        // класифікаторів, тому спершу створюємо всі сутності й закріплюємо за
        // ними ключі, і лише потім записуємо значення — інакше посилання
        // вперед указувало б у порожнечу.
        List<DeferredValues> deferred = new ArrayList<DeferredValues>();
        for (File file : files)
            if (file.getName().endsWith(".yaml"))
                deferred.add(readQualifier(file));

        // Властивості атрибутів посилаються на класифікатори, а значення
        // елементів вимагають уже налаштованих властивостей (Core.ElementList
        // без них відкидає значення). Звідси порядок: сутності → властивості
        // → значення.
        for (Map.Entry<String, Object> entry : deferredProperties.entrySet())
            applyProperty(entry.getKey(), entry.getValue());

        for (DeferredValues values : deferred)
            values.apply();

        // Потоки — після елементів: шляхи вкладень містять їхні ключі.
        readStreams(directory);

        // Без збереженої відповідності повторний експорт видав би інші
        // ідентифікатори, і формат перестав би бути стабільним між циклами.
        registry.save();
    }

    /**
     * Значення елементів одного класифікатора, відкладені до другого проходу.
     */
    private final class DeferredValues {

        private final List<Element> elements = new ArrayList<Element>();

        private final List<Map<String, Object>> values =
                new ArrayList<Map<String, Object>>();

        void add(Element element, Map<String, Object> value) {
            elements.add(element);
            values.add(value);
        }

        void apply() {
            for (int i = 0; i < elements.size(); i++) {
                Element element = elements.get(i);
                for (Map.Entry<String, Object> entry : values.get(i).entrySet())
                    applyValue(element, entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Відновлює потоки: налаштування моделі та вкладення користувача.
     * Стан інтерфейсу у файлах відсутній навмисно — див.
     * {@link ProjectWriter#writeStreams}.
     */
    @SuppressWarnings("unchecked")
    private void readStreams(File directory) throws IOException {
        File manifest = new File(directory, "streams.yaml");
        if (!manifest.isFile())
            return;
        Map<String, Object> document = readDocument(manifest);

        List<Object> properties = (List<Object>) document.get("properties");
        if (properties != null)
            for (Object row : properties) {
                Map<String, Object> map = (Map<String, Object>) row;
                String relative = text(map, "path");
                byte[] data = readBytes(new File(directory,
                        "properties/" + relative));
                if (data != null)
                    engine.setStream(ProjectWriter.PROPERTIES_PREFIX + relative,
                            data);
            }

        List<Object> attachments = (List<Object>) document.get("attachments");
        if (attachments != null)
            for (Object row : attachments) {
                Map<String, Object> map = (Map<String, Object>) row;
                long elementId = registry.numericId(ELEMENT,
                        text(map, "element"));
                Attribute attribute = resolveAttribute(text(map, "attribute"));
                if (elementId < 0 || attribute == null)
                    // Вкладення без елемента чи атрибута нікуди прив'язати.
                    continue;
                byte[] data = readBytes(new File(directory,
                        text(map, "file")));
                if (data == null)
                    continue;
                engine.setStream(ProjectWriter.ELEMENTS_PREFIX + elementId + "/"
                        + attribute.getId() + "/" + text(map, "name"), data);
            }
    }

    private static byte[] readBytes(File file) throws IOException {
        if (!file.isFile())
            return null;
        java.io.InputStream in = new java.io.FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0)
                out.write(buffer, 0, count);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private void checkSchema(Map<String, Object> project) throws IOException {
        Object schema = project.get("schema");
        if (!(schema instanceof Number))
            throw new IOException("У project.yaml немає поля schema");
        int version = ((Number) schema).intValue();
        if (version != ProjectWriter.SCHEMA_VERSION)
            throw new IOException("Версія формату " + version
                    + " не підтримується, очікувалась "
                    + ProjectWriter.SCHEMA_VERSION);
    }

    @SuppressWarnings("unchecked")
    private void readAttributes(File file) throws IOException {
        Map<String, Object> document = readDocument(file);
        List<Object> rows = (List<Object>) document.get("attributes");
        if (rows == null)
            return;

        for (Object row : rows) {
            Map<String, Object> map = (Map<String, Object>) row;
            String stableId = text(map, "id");
            AttributeType type = parseType(text(map, "type"));

            // Ключ роздає рушій. Нав'язати ключ із файлу не можна: у порожньому
            // рушії той самий номер уже може належати системному атрибуту
            // плагіна, і ми б перейменували чужий атрибут замість створення
            // свого.
            long known = registry.numericId(ATTRIBUTE, stableId);
            Attribute attribute = known >= 0 ? engine.getAttribute(known)
                    : engine.createAttribute(type);

            attribute.setName(text(map, "name"));
            engine.updateAttribute(attribute);
            registry.bind(ATTRIBUTE, stableId, attribute.getId());

            Object properties = map.get("properties");
            if (properties != null)
                deferredProperties.put(stableId, properties);
        }
    }

    @SuppressWarnings("unchecked")
    private DeferredValues readQualifier(File file) throws IOException {
        Map<String, Object> document = readDocument(file);
        String stableId = text(document, "id");

        long known = registry.numericId(QUALIFIER, stableId);
        Qualifier qualifier;
        if (known >= 0)
            qualifier = engine.getQualifier(known);
        else if (Boolean.TRUE.equals(document.get("system"))) {
            // Системний класифікатор зазвичай уже створив плагін — шукаємо за
            // іменем. Якщо плагін не підключений, створюємо самі: втратити
            // дані гірше, ніж мати класифікатор без свого плагіна.
            qualifier = systemQualifier(text(document, "name"));
            if (qualifier == null) {
                qualifier = engine.createSystemQualifier();
                qualifier.setName(text(document, "name"));
                engine.updateQualifier(qualifier);
            }
        } else
            qualifier = engine.createQualifier();
        long id = qualifier.getId();
        registry.bind(QUALIFIER, stableId, id);

        if (!qualifier.isSystem())
            qualifier.setName(text(document, "name"));

        List<Attribute> attributes = resolveAttributes(
                (List<Object>) document.get("attributes"));
        qualifier.getAttributes().clear();
        qualifier.getAttributes().addAll(attributes);

        // Набір системних атрибутів — частина моделі, а не рушія: саме він
        // робить класифікатор функційним блоком IDEF0. Свіжий рушій дає лише
        // типовий набір, тому відновлюємо збережений.
        List<Attribute> system = resolveAttributes(
                (List<Object>) document.get("system-attributes"));
        // Доповнюємо, а не замінюємо: частину системних атрибутів плагін
        // додає сам при відкритті (наприклад, F_PAGE_SIZE у старих моделях),
        // і заміна списку списком із файлу їх би прибрала.
        for (Attribute attribute : system)
            if (!qualifier.getSystemAttributes().contains(attribute))
                qualifier.getSystemAttributes().add(attribute);

        String nameAttribute = text(document, "name-attribute");
        if (nameAttribute != null) {
            Attribute forName = resolveAttribute(nameAttribute);
            if (forName != null)
                qualifier.setAttributeForName(forName.getId());
        }

        engine.updateQualifier(qualifier);

        // Перечитуємо: рушій міг доповнити класифікатор системними атрибутами.
        qualifier = engine.getQualifier(id);

        return readElements(qualifier,
                (List<Object>) document.get("elements"));
    }

    @SuppressWarnings("unchecked")
    private DeferredValues readElements(Qualifier qualifier,
                                        List<Object> rows) {
        DeferredValues deferred = new DeferredValues();
        if (rows == null)
            return deferred;

        for (Object row : rows) {
            Map<String, Object> map = (Map<String, Object>) row;
            String stableId = text(map, "id");

            Map<String, Object> values = (Map<String, Object>) map.get("values");

            long known = registry.numericId(ELEMENT, stableId);
            Element element;
            if (known >= 0)
                element = engine.getElement(known);
            else if (qualifier.isSystem())
                // Елементи системних класифікаторів (базові функції моделей,
                // звіти) плагіни створюють самі при відкритті. Якщо такий уже
                // є — беремо його, інакше отримали б дублікат.
                element = matchOrCreate(qualifier, values);
            else
                element = engine.createElement(qualifier.getId());
            registry.bind(ELEMENT, stableId, element.getId());

            if (values != null)
                deferred.add(element, values);
        }
        return deferred;
    }

    /**
     * Відновлює значення атрибута з YAML і записує його в елемент.
     * <p>
     * Шлях дзеркальний до експорту: мапи полів перетворюються на персистенти,
     * а конвертер плагіна складає з них об'єкт, який розуміє рушій.
     */
    /**
     * Записує конфігурацію плагіна атрибута (значення з {@code elementId = -1}).
     */
    private void applyProperty(String attributeId, Object value) {
        Attribute attribute = resolveAttribute(attributeId);
        if (attribute == null)
            return;
        AttributePlugin plugin = factory.getAttributePlugin(attribute
                .getAttributeType());
        if (plugin == null)
            return;
        apply(null, attribute, plugin.getAttributePropertyPersistents(),
                value, -1L);
    }

    private void applyValue(Element element, String attributeId, Object value) {
        Attribute attribute = resolveAttribute(attributeId);
        if (attribute == null)
            return;

        AttributePlugin plugin = factory.getAttributePlugin(attribute
                .getAttributeType());
        if (plugin == null)
            return;

        apply(element, attribute, plugin.getAttributePersistents(), value,
                element.getId());
    }

    @SuppressWarnings("unchecked")
    private void apply(Element element, Attribute attribute,
                       Class<? extends Persistent>[] classes, Object value,
                       long elementId) {
        if (classes.length == 0)
            return;

        List<Object> tables = asTables(value, classes.length);
        List<Persistent>[] lists = new List[classes.length];
        for (int i = 0; i < classes.length; i++) {
            List<Persistent> rows = new ArrayList<Persistent>();
            if (i < tables.size())
                for (Object row : (List<Object>) tables.get(i)) {
                    Persistent persistent;
                    try {
                        persistent = codec.fromValue(classes[i], row);
                    } catch (RuntimeException e) {
                        throw new IllegalStateException("Атрибут «"
                                + attribute.getName() + "» типу "
                                + attribute.getAttributeType()
                                + " (таблиця " + i + " з " + classes.length
                                + "): " + e.getMessage(), e);
                    }
                    persistent.setValueBranchId(0L);
                    rows.add(persistent);
                }
            lists[i] = rows;
        }

        AttributePlugin plugin = factory.getAttributePlugin(attribute
                .getAttributeType());
        Object object = plugin.getAttributeConverter().toObject(lists,
                elementId, attribute.getId(), engine);
        engine.setAttribute(element, attribute, object);
    }

    /**
     * Розгортає скорочення, які застосував {@link ProjectWriter}: одну таблицю
     * з одним рядком він пише як саме значення, а не як список списків.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> asTables(Object value, int tableCount) {
        List<Object> tables = new ArrayList<Object>(tableCount);
        if (tableCount == 1) {
            if (value instanceof List) {
                tables.add(value);
            } else {
                List<Object> rows = new ArrayList<Object>(1);
                rows.add(value);
                tables.add(rows);
            }
            return tables;
        }
        if (value instanceof List)
            for (Object table : (List<Object>) value)
                tables.add(table instanceof List ? table
                        : java.util.Collections.singletonList(table));
        return tables;
    }

    private List<Attribute> resolveAttributes(List<Object> ids) {
        List<Attribute> result = new ArrayList<Attribute>();
        if (ids == null)
            return result;
        for (Object id : ids) {
            Attribute attribute = resolveAttribute(id.toString());
            if (attribute != null)
                result.add(attribute);
        }
        return result;
    }

    /**
     * Знаходить атрибут за посиланням із файлу.
     * <p>
     * Системні адресуються за іменем: у кожному рушії плагіни створюють їх
     * заново й з іншими ключами, тому числовий ключ із файлу вказував би не на
     * той атрибут. Користувацькі — за стабільним ідентифікатором.
     *
     * @return {@code null}, якщо атрибута немає (наприклад, не підключений
     * плагін, який його оголошує)
     */
    private Attribute resolveAttribute(String reference) {
        if (reference.startsWith(ProjectWriter.SYSTEM_PREFIX))
            return systemAttributes().get(
                    reference.substring(ProjectWriter.SYSTEM_PREFIX.length()));
        long numericId = registry.numericId(ATTRIBUTE, reference);
        return numericId < 0 ? null : engine.getAttribute(numericId);
    }

    private Map<String, Attribute> systemAttributes() {
        if (systemAttributes == null) {
            systemAttributes = new java.util.HashMap<String, Attribute>();
            for (Attribute attribute : engine.getSystemAttributes())
                systemAttributes.put(attribute.getName(), attribute);
        }
        return systemAttributes;
    }

    /**
     * Ключі всіх наявних атрибутів, разом із системними.
     * <p>
     * {@code getAttributes()} системних не повертає, а свіжий рушій їх уже
     * створив: без цього імпорт намагався б створити атрибут із зайнятим
     * ключем.
     */
    /**
     * Ключі всіх наявних атрибутів, разом із системними: свіжий рушій уже
     * створив останні, і спроба зайняти той самий ключ впала б.
     */
    private Set<Long> attributeIds() {
        Set<Long> ids = new HashSet<Long>();
        for (Attribute attribute : engine.getAttributes())
            ids.add(Long.valueOf(attribute.getId()));
        for (Attribute attribute : engine.getSystemAttributes())
            ids.add(Long.valueOf(attribute.getId()));
        return ids;
    }

    /**
     * Шукає в системному класифікаторі елемент із такою самою назвою, а якщо
     * не знаходить — створює новий.
     */
    private Element matchOrCreate(Qualifier qualifier,
                                  Map<String, Object> values) {
        String name = nameOf(qualifier, values);
        if (name != null)
            for (Element candidate : engine.getElements(qualifier.getId()))
                if (name.equals(candidate.getName())
                        && registry.stableId(ELEMENT, candidate.getId()) != null
                        && !bound.contains(Long.valueOf(candidate.getId()))) {
                    bound.add(Long.valueOf(candidate.getId()));
                    return candidate;
                }
        return engine.createElement(qualifier.getId());
    }

    /**
     * @return значення атрибута-назви з файлу або {@code null}
     */
    private String nameOf(Qualifier qualifier, Map<String, Object> values) {
        if (values == null || qualifier.getAttributeForName() < 0)
            return null;
        Attribute forName = engine.getAttribute(
                qualifier.getAttributeForName());
        if (forName == null)
            return null;
        Object value = values.get(ProjectWriter.SYSTEM_PREFIX
                + forName.getName());
        if (value == null)
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                Attribute attribute = resolveAttribute(entry.getKey());
                if (attribute != null && attribute.getId() == forName.getId()) {
                    value = entry.getValue();
                    break;
                }
            }
        return value == null ? null : value.toString();
    }

    private Qualifier systemQualifier(String name) {
        for (Qualifier qualifier : engine.getSystemQualifiers())
            if (qualifier.getName().equals(name))
                return qualifier;
        return null;
    }

    private Set<Long> qualifierIds() {
        Set<Long> ids = new HashSet<Long>();
        for (Qualifier qualifier : engine.getQualifiers())
            ids.add(Long.valueOf(qualifier.getId()));
        for (Qualifier qualifier : engine.getSystemQualifiers())
            ids.add(Long.valueOf(qualifier.getId()));
        return ids;
    }

    private static AttributeType parseType(String value) throws IOException {
        int dot = value.indexOf('.');
        if (dot <= 0)
            throw new IOException("Очікувався тип у вигляді «Плагін.Тип»,"
                    + " а не «" + value + "»");
        return new AttributeType(value.substring(0, dot),
                value.substring(dot + 1));
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> readDocument(File file)
            throws IOException {
        if (!file.isFile())
            throw new IOException("Не знайдено " + file);
        InputStream in = new FileInputStream(file);
        try {
            return YamlFormat.read(in);
        } finally {
            in.close();
        }
    }
}
