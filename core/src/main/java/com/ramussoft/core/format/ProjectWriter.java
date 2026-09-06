package com.ramussoft.core.format;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ramussoft.common.Attribute;
import com.ramussoft.common.Element;
import com.ramussoft.common.Engine;
import com.ramussoft.common.Metadata;
import com.ramussoft.common.Qualifier;
import com.ramussoft.common.persistent.Persistent;
import com.ramussoft.core.format.yaml.YamlFormat;

/**
 * Записує модель у дерево YAML-файлів.
 * <p>
 * На відміну від {@code .rsf}, який є дампом таблиць БД, тут обхід іде через
 * {@link Engine}: у файл потрапляють класифікатори, елементи та значення
 * атрибутів, а не рядки {@code <f id="3">38</f>}. Числові ключі БД
 * замінюються стабільними ідентифікаторами {@link StableIds}, тому посилання
 * переживають перейменування.
 * <p>
 * Запис детермінований: усе впорядковано за стабільним ключем, жодних міток
 * часу у файлах немає.
 */
public class ProjectWriter {

    /**
     * Версія розкладки файлів. Зростає, коли змінюється структура, а не вміст.
     */
    public static final int SCHEMA_VERSION = 3;

    private static final String QUALIFIER = "qualifier";

    private static final String ATTRIBUTE = "attribute";

    private static final String ELEMENT = "element";

    /**
     * Позначка посилання на системний атрибут — за іменем, а не за ключем.
     */
    static final String SYSTEM_PREFIX = "sys:";

    static final String PROPERTIES_PREFIX = "/properties/";

    static final String ELEMENTS_PREFIX = "/elements/";

    /**
     * Стан інтерфейсу: персональний, у версіонованих файлах йому не місце.
     */
    static final String USER_PREFIX = "/user/";

    /**
     * Системні класифікатори, що є дзеркалами метаданих рушія: по одному
     * елементу на класифікатор і на атрибут.
     * <p>
     * Їх не експортуємо. Вміст цих таблиць рушій відтворює сам із
     * класифікаторів та атрибутів, які ми записуємо явно; спроба відновити їх
     * як звичайні елементи призводить до створення дублікатів класифікаторів.
     */
    private static final String[] DERIVED_QUALIFIERS = {
            "QualifiersQualifier", "AttributesQualifier", "HistoryQualifier"};

    private final Engine engine;

    private final StableIdRegistry registry;

    private final PersistentCodec codec;

    public ProjectWriter(Engine engine) {
        this.engine = engine;
        this.registry = new StableIdRegistry(engine);
        this.codec = new PersistentCodec(registry, engine);
    }

    public void write(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Не вдалося створити каталог " + directory);

        writeDocument(new File(directory, "project.yaml"), project());
        writeDocument(new File(directory, "attributes.yaml"), attributes());

        writeStreams(directory);

        File qualifiers = new File(directory, "qualifiers");
        if (!qualifiers.isDirectory() && !qualifiers.mkdirs())
            throw new IOException("Не вдалося створити каталог " + qualifiers);

        for (Qualifier qualifier : sortedQualifiers()) {
            String name = StableIds.slug(qualifier.getName()) + "--"
                    + registry.stableId(QUALIFIER, qualifier.getId())
                    + ".yaml";
            writeDocument(new File(qualifiers, name), qualifier(qualifier));
        }
    }

    /**
     * Записує потоки проєкту — дані, що живуть поза таблицями.
     * <p>
     * Вони розпадаються на три шари, і формат розводить їх навмисно:
     * <ul>
     * <li>{@code /properties/*} — налаштування моделі, лягають окремими
     * файлами під {@code properties/} і версіонуються;</li>
     * <li>{@code /elements/<елемент>/<атрибут>/*} — вкладення користувача
     * (звіти, файли); числові ключі в шляху замінюються стабільними, інакше
     * після імпорту вкладення прив'язалося б до іншого елемента;</li>
     * <li>{@code /user/*} — стан інтерфейсу; не експортується взагалі: він
     * персональний і в git йому не місце.</li>
     * </ul>
     */
    private void writeStreams(File directory) throws IOException {
        List<Object> properties = new ArrayList<Object>();
        List<Object> attachments = new ArrayList<Object>();

        String[] names = engine.getStreamNames();
        Arrays.sort(names);

        for (String name : names) {
            if (name.startsWith(USER_PREFIX))
                continue;
            if (StableIdRegistry.PATH.equals(name))
                // Реєстр відновлюється з прив'язок під час імпорту.
                continue;

            byte[] data = engine.getStream(name);
            if (data == null)
                continue;

            if (name.startsWith(PROPERTIES_PREFIX)) {
                String relative = name.substring(PROPERTIES_PREFIX.length());
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("path", relative);
                properties.add(row);
                writeBytes(new File(directory, "properties/" + relative), data);
            } else if (name.startsWith(ELEMENTS_PREFIX)) {
                Map<String, Object> row = attachmentRow(name);
                if (row == null)
                    continue;
                // Ім'я файлу виводиться з самого вкладення, а не з лічильника:
                // інакше додавання одного вкладення зсуває всі наступні, і git
                // показує зміну там, де її немає.
                String file = "attachments/" + row.get("element") + "/"
                        + safeSegment(row.get("attribute").toString()) + "/"
                        + safeSegment(row.get("name").toString());
                row.put("file", file);
                attachments.add(row);
                writeBytes(new File(directory, file), data);
            }
        }

        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("schema", java.lang.Integer.valueOf(SCHEMA_VERSION));
        if (!properties.isEmpty())
            document.put("properties", properties);
        if (!attachments.isEmpty())
            document.put("attachments", attachments);
        writeDocument(new File(directory, "streams.yaml"), document);
    }

    /**
     * @return опис вкладення зі стабільними посиланнями або {@code null}, якщо
     * шлях не має очікуваного вигляду
     */
    private Map<String, Object> attachmentRow(String name) {
        String rest = name.substring(ELEMENTS_PREFIX.length());
        int firstSlash = rest.indexOf('/');
        if (firstSlash < 0)
            return null;
        int secondSlash = rest.indexOf('/', firstSlash + 1);
        if (secondSlash < 0)
            return null;
        long elementId;
        long attributeId;
        try {
            elementId = Long.parseLong(rest.substring(0, firstSlash));
            attributeId = Long.parseLong(rest.substring(firstSlash + 1,
                    secondSlash));
        } catch (NumberFormatException e) {
            return null;
        }
        Attribute attribute = engine.getAttribute(attributeId);
        if (attribute == null)
            return null;

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("element", registry.stableId(ELEMENT, elementId));
        row.put("attribute", attributeRef(attribute));
        row.put("name", rest.substring(secondSlash + 1));
        return row;
    }

    /**
     * Робить із посилання чи назви безпечний сегмент шляху: {@code sys:} та
     * інші символи, неприйнятні в іменах файлів, замінюються дефісом.
     */
    static String safeSegment(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '-'
                    || c == '_' ? c : '-');
        }
        return sb.length() == 0 ? "unnamed" : sb.toString();
    }

    private static void writeBytes(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Не вдалося створити каталог " + parent);
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private Map<String, Object> project() {
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("schema", java.lang.Integer.valueOf(SCHEMA_VERSION));
        document.put("application", Metadata.getApplicationName());
        // Версія застосунку, який записав файл. Мітки часу навмисно немає:
        // вона робила б кожне збереження унікальним.
        document.put("application-version", Metadata.getApplicationVersion());
        return document;
    }

    /**
     * Посилання на атрибут.
     * <p>
     * Системні атрибути створює не проєкт, а плагіни, і в кожному рушії вони
     * отримують власні числові ключі. Виводити з такого ключа стабільний
     * ідентифікатор не можна — після імпорту він указував би на інший атрибут.
     * Тому системні адресуються за іменем: воно задане плагіном, унікальне
     * і до того ж читабельне ({@code F_VISUAL_DATA} замість {@code vakjk8}).
     */
    String attributeRef(Attribute attribute) {
        if (attribute.isSystem())
            return SYSTEM_PREFIX + attribute.getName();
        return registry.stableId(ATTRIBUTE, attribute.getId());
    }

    private Map<String, Object> attributes() {
        // Лише користувацькі: системні відтворить рушій із плагінів.
        List<Attribute> all = new ArrayList<Attribute>(engine.getAttributes());
        Collections.sort(all, new Comparator<Attribute>() {
            @Override
            public int compare(Attribute a, Attribute b) {
                return Long.compare(a.getId(), b.getId());
            }
        });

        List<Object> rows = new ArrayList<Object>(all.size());
        for (Attribute attribute : all) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", attributeRef(attribute));
            row.put("name", attribute.getName());
            row.put("type", attribute.getAttributeType().toString());
            // Властивості атрибута — це конфігурація його плагіна (для
            // Core.ElementList, наприклад, пов'язані класифікатори). Без них
            // значення елементів не відновлюються.
            Object properties = value(-1L, attribute);
            if (properties != null)
                row.put("properties", properties);
            rows.add(row);
        }

        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("schema", java.lang.Integer.valueOf(SCHEMA_VERSION));
        document.put("attributes", rows);
        return document;
    }

    private Map<String, Object> qualifier(Qualifier qualifier) {
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("schema", java.lang.Integer.valueOf(SCHEMA_VERSION));
        document.put("id", registry.stableId(QUALIFIER, qualifier.getId()));
        document.put("name", qualifier.getName());
        if (qualifier.isSystem())
            // Системний класифікатор створює плагін, а не файл: при імпорті
            // його шукають за іменем, як і системні атрибути.
            document.put("system", Boolean.TRUE);

        List<Object> attributeIds = new ArrayList<Object>();
        for (Attribute attribute : qualifier.getAttributes())
            attributeIds.add(attributeRef(attribute));
        document.put("attributes", attributeIds);

        // Системні атрибути тримають, зокрема, всю геометрію IDEF0
        // (F_VISUAL_DATA, F_BOUNDS, сектори). Без них експорт був би
        // не моделлю, а лише її назвами.
        List<String> systemIds = new ArrayList<String>();
        for (Attribute attribute : systemAttributes(qualifier))
            systemIds.add(attributeRef(attribute));
        // Порядок задають плагіни, він несуттєвий і між запусками різний —
        // сортуємо, щоб файл не змінювався без змін у моделі.
        Collections.sort(systemIds);
        if (!systemIds.isEmpty())
            document.put("system-attributes", new ArrayList<Object>(systemIds));

        if (qualifier.getAttributeForName() >= 0) {
            Attribute forName = engine.getAttribute(
                    qualifier.getAttributeForName());
            if (forName != null)
                document.put("name-attribute", attributeRef(forName));
        }

        document.put("elements", elements(qualifier));
        return document;
    }

    private List<Object> elements(Qualifier qualifier) {
        List<Element> all = new ArrayList<Element>(
                engine.getElements(qualifier.getId()));
        Collections.sort(all, new Comparator<Element>() {
            @Override
            public int compare(Element a, Element b) {
                return Long.compare(a.getId(), b.getId());
            }
        });

        List<Object> rows = new ArrayList<Object>(all.size());
        for (Element element : all) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", registry.stableId(ELEMENT, element.getId()));
            Map<String, Object> values = values(qualifier, element);
            if (!values.isEmpty())
                row.put("values", values);
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> values(Qualifier qualifier, Element element) {
        // Ключі — стабільні посилання, а не числові ключі: сортування за ними
        // не залежить від того, які номери роздав конкретний рушій.
        Map<String, Object> values = new java.util.TreeMap<String, Object>();
        for (Attribute attribute : allAttributes(qualifier)) {
            Object value = value(element.getId(), attribute);
            if (value != null)
                values.put(attributeRef(attribute), value);
        }
        return values;
    }

    private List<Attribute> systemAttributes(Qualifier qualifier) {
        List<Attribute> system = qualifier.getSystemAttributes();
        return system == null ? new ArrayList<Attribute>()
                : new ArrayList<Attribute>(system);
    }

    /**
     * Звичайні та системні атрибути разом, у стабільному порядку.
     */
    private List<Attribute> allAttributes(Qualifier qualifier) {
        List<Attribute> all = new ArrayList<Attribute>(
                qualifier.getAttributes());
        all.addAll(systemAttributes(qualifier));
        Collections.sort(all, new Comparator<Attribute>() {
            @Override
            public int compare(Attribute a, Attribute b) {
                return Long.compare(a.getId(), b.getId());
            }
        });
        return all;
    }

    /**
     * Значення атрибута у вигляді, придатному для YAML.
     * <p>
     * Плагін атрибута може зберігати значення в кількох таблицях, тому
     * загальна форма — список списків рядків. Найпоширеніший випадок (одна
     * таблиця, один рядок) згортається до самої мапи полів, інакше файл
     * потонув би у вкладеності там, де насправді одне значення.
     *
     * @return {@code null}, якщо значення не задано
     */
    private Object value(long elementId, Attribute attribute) {
        List<Persistent>[] lists;
        try {
            lists = engine.getBinaryAttribute(elementId, attribute.getId());
        } catch (RuntimeException e) {
            // Читання значення не має валити експорт цілої моделі: краще
            // втратити одне поле й повідомити, ніж не отримати файлу взагалі.
            return null;
        }
        if (lists == null || lists.length == 0)
            return null;

        List<Object> tables = new ArrayList<Object>(lists.length);
        boolean empty = true;
        for (List<Persistent> list : lists) {
            List<Object> rows = new ArrayList<Object>();
            if (list != null)
                for (Persistent persistent : list) {
                    rows.add(codec.toValue(persistent));
                    empty = false;
                }
            tables.add(rows);
        }
        if (empty)
            return null;

        if (tables.size() == 1) {
            List<?> rows = (List<?>) tables.get(0);
            if (rows.size() == 1)
                return rows.get(0);
            return rows;
        }
        return tables;
    }

    /**
     * Усі класифікатори, разом із системними.
     * <p>
     * Системні — не службовий шум: у них лежать базові функції моделей IDEF0
     * та звіти. Без них імпортована модель не має кореневої функції, і
     * діаграма не будується.
     */
    private List<Qualifier> sortedQualifiers() {
        Set<Long> derived = derivedQualifierIds();
        List<Qualifier> all = new ArrayList<Qualifier>(engine.getQualifiers());
        all.addAll(engine.getSystemQualifiers());
        for (java.util.Iterator<Qualifier> i = all.iterator(); i.hasNext(); )
            if (derived.contains(Long.valueOf(i.next().getId())))
                i.remove();
        Collections.sort(all, new Comparator<Qualifier>() {
            @Override
            public int compare(Qualifier a, Qualifier b) {
                return Long.compare(a.getId(), b.getId());
            }
        });
        return all;
    }

    private Set<Long> derivedQualifierIds() {
        Set<Long> ids = new java.util.HashSet<Long>();
        for (String name : DERIVED_QUALIFIERS) {
            Object property = engine.getPluginProperty("Core", name);
            if (property instanceof Qualifier)
                ids.add(Long.valueOf(((Qualifier) property).getId()));
        }
        return ids;
    }

    private static void writeDocument(File file, Map<String, Object> document)
            throws IOException {
        OutputStream out = new FileOutputStream(file);
        try {
            YamlFormat.write(document, out);
        } finally {
            out.close();
        }
    }
}
