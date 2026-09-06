package com.ramussoft.core.format;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.ramussoft.common.Engine;

/**
 * Відповідність «стабільний ідентифікатор ↔ числовий ключ», збережена в самому
 * проєкті.
 * <p>
 * Спокуслива ідея — виводити ідентифікатор із числового ключа й нічого не
 * зберігати — не працює: ключі роздає рушій, і в іншому рушії ті самі номери
 * зайняті іншими сутностями. Зокрема, атрибут користувача з ключем 5 у
 * порожньому рушії наштовхується на системний атрибут плагіна з тим самим
 * ключем. Тому імпорт не нав'язує ключі, а дозволяє рушію роздати свої і
 * запам'ятовує відповідність.
 * <p>
 * Зберігається як потік усередині проєкту ({@link Engine#setProperties}), тож
 * переживає і {@code .rsf}, і новий формат, і не потребує змін схеми БД.
 */
public class StableIdRegistry {

    public static final String PATH = "/properties/stable-ids.xml";

    private final Engine engine;

    /**
     * {@code вид:стабільний-id → числовий ключ}
     */
    private final Map<String, Long> toNumeric = new HashMap<String, Long>();

    /**
     * {@code вид:числовий-ключ → стабільний id}
     */
    private final Map<String, String> toStable = new HashMap<String, String>();

    public StableIdRegistry(Engine engine) {
        this.engine = engine;
        load();
    }

    private void load() {
        Properties properties = engine.getProperties(PATH);
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            try {
                long numericId = Long.parseLong(value);
                toNumeric.put(key, Long.valueOf(numericId));
                toStable.put(numericKey(kindOf(key), numericId), idOf(key));
            } catch (NumberFormatException e) {
                // Пошкоджений рядок не має валити відкриття проєкту: без нього
                // ідентифікатор просто буде виведений з числового ключа.
            }
        }
    }

    /**
     * Стабільний ідентифікатор сутності.
     *
     * @return збережений ідентифікатор, а якщо його немає — виведений із
     * числового ключа
     */
    public String stableId(String kind, long numericId) {
        String stored = toStable.get(numericKey(kind, numericId));
        if (stored != null)
            return stored;
        return StableIds.of(kind, numericId);
    }

    /**
     * Числовий ключ за стабільним ідентифікатором.
     *
     * @return {@code -1}, якщо відповідності немає — сутність ще треба створити
     */
    public long numericId(String kind, String stableId) {
        Long stored = toNumeric.get(key(kind, stableId));
        return stored == null ? -1L : stored.longValue();
    }

    /**
     * Запам'ятовує відповідність. Викликається імпортом після того, як рушій
     * видав свій ключ.
     */
    public void bind(String kind, String stableId, long numericId) {
        toNumeric.put(key(kind, stableId), Long.valueOf(numericId));
        toStable.put(numericKey(kind, numericId), stableId);
    }

    public void save() {
        Properties properties = new Properties();
        for (Map.Entry<String, Long> entry : toNumeric.entrySet())
            properties.setProperty(entry.getKey(),
                    Long.toString(entry.getValue().longValue()));
        engine.setProperties(PATH, properties);
    }

    private static String key(String kind, String stableId) {
        return kind + ':' + stableId;
    }

    private static String numericKey(String kind, long numericId) {
        return kind + '#' + numericId;
    }

    private static String kindOf(String key) {
        return key.substring(0, key.indexOf(':'));
    }

    private static String idOf(String key) {
        return key.substring(key.indexOf(':') + 1);
    }
}
