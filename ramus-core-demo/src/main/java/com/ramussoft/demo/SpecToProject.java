package com.ramussoft.demo;

import java.awt.Color;
import java.awt.Dimension;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dsoft.pb.types.FRectangle;
import com.ramussoft.common.AccessRules;
import com.ramussoft.common.Attribute;
import com.ramussoft.common.AttributeType;
import com.ramussoft.common.Engine;
import com.ramussoft.common.Element;
import com.ramussoft.common.Qualifier;
import com.ramussoft.core.attribute.simple.HierarchicalPersistent;
import com.ramussoft.core.attribute.simple.HierarchicalPlugin;
import com.ramussoft.core.attribute.standard.StandardAttributesPlugin;
import com.ramussoft.common.journal.Journaled;
import com.ramussoft.core.format.yaml.YamlFormat;
import com.ramussoft.core.impl.FileIEngineImpl;
import com.ramussoft.database.FileDatabaseFactory;
import com.ramussoft.database.MemoryDatabase;
import com.ramussoft.idef0.IDEF0Plugin;
import com.ramussoft.idef0.NDataPluginFactory;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.Sector;
import com.ramussoft.pb.Stream;
import com.ramussoft.idef0.attribute.SectorPointPersistent;
import com.ramussoft.pb.data.negine.NSector;
import com.ramussoft.pb.data.negine.NSectorBorder;
import com.ramussoft.pb.idef.elements.PaintSector;
import com.ramussoft.pb.idef.elements.ReplaceStreamType;
import com.ramussoft.pb.idef.elements.SectorRefactor;
import com.ramussoft.pb.idef.visual.MovingArea;
import com.ramussoft.pb.idef.visual.MovingLabel;
import com.ramussoft.pb.idef.visual.MovingPanel;
import com.ramussoft.pb.print.PIDEF0painter;

/**
 * Будує проєкт Ramus із короткого текстового опису моделі.
 * <p>
 * Перша половина docs-as-code. Формат проєкту дозволяє правити наявні
 * значення, але не створювати сутності: ідентифікатор — оборотне
 * перетворення ключа бази, і вигаданий у файлі {@code id} мовчки зламає
 * посилання. Тому опис читає не текстовий редактор, а рушій: блоки, стрілки
 * й потоки створюються тим самим API, яким їх створює застосунок, а ключі
 * роздають лічильники.
 *
 * <pre>
 * java -cp ... com.ramussoft.demo.SpecToProject опис.yaml Модель.ramus
 * </pre>
 * <p>
 * Опис:
 *
 * <pre>
 * model: 'Обробка замовлення'      # назва моделі
 * name: 'Обробити замовлення'      # назва кореневого блока; типово = model
 * type: 'idef0'                    # idef0 | dfd | dfds
 * blocks:                          # блоки діаграми A0
 * - id: 'accept'                   # локальне ім'я для посилань зі стрілок
 *   name: 'Прийняти замовлення'
 *   x: 120
 *   y: 100
 *   width: 160
 *   height: 90
 *   color: '#b7f0c0'
 *   blocks: [...]                  # декомпозиція: діаграма A1
 *   arrows: [...]                  # стрілки на діаграмі A1
 * arrows:                          # стрілки на діаграмі A0
 * - name: 'Замовлення клієнта'
 *   from: 'border'                 # 'border' або id блока
 *   to: 'accept'
 *   to-side: 'input'               # input | control | output | mechanism
 *   color: '#c0392b'               # колір лінії та підпису
 * id: 'system'                     # id кореневого блока для стрілок A-0
 * context-arrows:                  # стрілки контекстної діаграми A-0
 * - name: 'Замовлення клієнта'
 *   from: 'border'
 *   to: 'system'
 * </pre>
 */
public final class SpecToProject {

    /**
     * Робоче поле діаграми у внутрішніх координатах моделі.
     * <p>
     * Це {@code CLIENT_WIDTH} і {@code CLIENT_HEIGHT} з
     * {@link MovingArea}: 800 на 555 мінус верхня й нижня смуги рамки. Блок,
     * що вийшов за ці межі, застосунок намалює поверх рамки.
     */
    private static final double CANVAS_WIDTH = 800.0;

    private static final double CANVAS_HEIGHT = 444.0;

    /**
     * Розмір, у якому розкладається діаграма під час нормалізації. На
     * координати моделі не впливає — від нього залежать лише метрики
     * шрифту, якими рахується рамка підпису.
     */
    private static final Dimension LAYOUT_SIZE = new Dimension(1200, 900);

    /**
     * Розмір кореневого блока на контекстній діаграмі — такий самий, як у
     * зразкових моделях {@code dest/doc}.
     */
    private static final double ROOT_WIDTH = 144.0;

    private static final double ROOT_HEIGHT = 80.0;

    /** Відступ смуги обходу від блоків. */
    private static final double RAIL_MARGIN = 25.0;

    private SpecToProject() {
    }

    /**
     * Розібрана стрілка: кінці вже знайдено серед блоків діаграми,
     * {@code null} означає межу діаграми.
     */
    private static final class Arrow {

        private String name;

        private Function from;

        private Function to;

        private int fromSide;

        private int toSide;

        /** {@code null} — звичайний маршрут, інакше 'under' або 'over'. */
        private String route;

        private Color color;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Використання: SpecToProject <опис.yaml>"
                    + " <каталог проєкту>");
            System.exit(2);
            return;
        }

        File spec = new File(args[0]);
        if (!spec.isFile()) {
            System.err.println("Не знайдено файл опису: " + spec);
            System.exit(2);
            return;
        }
        File target = new File(args[1]);

        // Рушій тягне за собою Swing-класи, але вікна не відкриває.
        System.setProperty("java.awt.headless", "true");

        Map<String, Object> document = read(spec);

        MemoryDatabase database = (MemoryDatabase) FileDatabaseFactory
                .createDatabase();
        try {
            Engine engine = database.getEngine(null);
            AccessRules rules = database.getAccessRules(null);

            Builder builder = new Builder(engine, rules);
            builder.build(document);

            FileIEngineImpl impl = (FileIEngineImpl) engine.getDeligate();
            impl.saveProject(target);
            impl.close();

            System.out.println("Створено " + target.getAbsolutePath() + ": "
                    + builder.blocks + " блоків, " + builder.arrows
                    + " стрілок, " + builder.streams.size() + " потоків");
        } finally {
            database.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(File file) throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            // Той самий читач, що й у формату проєкту: вузький діалект,
            // дублікат ключа — помилка, а не остання виграла.
            Object loaded = YamlFormat.load().loadFromInputStream(in);
            if (!(loaded instanceof Map))
                throw new IllegalArgumentException(
                        "Очікується мапа на верхньому рівні опису");
            return (Map<String, Object>) loaded;
        } finally {
            in.close();
        }
    }

    /**
     * Складання моделі. Стан — лічильники й таблиця потоків: один потік
     * обслуговує всі стрілки з тією самою назвою, як і в застосунку.
     */
    private static final class Builder {

        private final Engine engine;

        private final AccessRules rules;

        private final Map<String, Stream> streams = new HashMap<String, Stream>();

        /**
         * Колір стрілки за ключем її елемента. Задати його одразу не можна:
         * колір живе у візуальному стані, який пишеться через розкладку, а
         * вона будується вже після того, як усі стрілки створені.
         */
        private final Map<Long, Color> colors = new HashMap<Long, Color>();

        private DataPlugin plugin;

        private int blocks;

        private int arrows;

        Builder(Engine engine, AccessRules rules) {
            this.engine = engine;
            this.rules = rules;
        }

        void build(Map<String, Object> document) {
            String model = text(document.get("model"), "Модель");
            String root = text(document.get("name"), model);

            Journaled journaled = engine instanceof Journaled
                    ? (Journaled) engine : null;
            if (journaled != null)
                journaled.startUserTransaction();

            // Класифікатор моделі мусить мати атрибут-назву: саме його
            // застосунок показує як назву блока, і без нього setName блока
            // нема куди записати.
            Attribute name = engine.createAttribute(new AttributeType("Core",
                    "Text", true));
            name.setName("Назва");
            engine.updateAttribute(name);

            Qualifier qualifier = engine.createQualifier();
            qualifier.setName(model);
            qualifier.getAttributes().add(name);
            qualifier.setAttributeForName(name.getId());
            engine.updateQualifier(qualifier);

            // updateQualifier повертає новий екземпляр, тож далі працюємо з
            // перечитаним, інакше зміни ляжуть на застарілу копію.
            qualifier = engine.getQualifier(qualifier.getId());
            IDEF0Plugin.installFunctionAttributes(qualifier, engine);
            qualifier = engine.getQualifier(qualifier.getId());

            registerInModelTree(qualifier, model);

            plugin = NDataPluginFactory.getDataPlugin(qualifier, engine, rules);

            Function base = plugin.getBaseFunction();
            // Кореневу функцію ніхто не створював — вона з'являється разом із
            // класифікатором, тож і setDefaultValues на ній не спрацював.
            // Порожній блоб замість відсутнього: розкладка контекстної
            // діаграми на null виходить одразу й не бачить жодної стрілки.
            if (base.getSectorData() == null)
                base.setSectorData(new byte[0]);
            // Кореневий блок описується тими ж полями, що й решта: верхній
            // рівень опису — це його блок.
            Function top = createFunction(base, root, document);
            top.setDecompositionType(decompositionType(document.get("type")));

            // На контекстній діаграмі блок один, і стоїть він посередині —
            // інакше ляже в кут із типовим розміром щойно створеного блока.
            top.setBounds(new FRectangle(
                    number(document.get("x"),
                            (CANVAS_WIDTH - ROOT_WIDTH) / 2.0),
                    number(document.get("y"),
                            (CANVAS_HEIGHT - ROOT_HEIGHT) / 2.0),
                    number(document.get("width"), ROOT_WIDTH),
                    number(document.get("height"), ROOT_HEIGHT)));

            children(top, document);

            // Стрілки контекстної діаграми A-0. Її «блоки» — це сам
            // кореневий блок, тож на нього посилаються за його id.
            Map<String, Function> context = new HashMap<String, Function>();
            context.put(text(document.get("id"), "root"), top);
            arrows(base, document.get("context-arrows"), context);

            normalize(base);

            if (journaled != null)
                journaled.commitUserTransaction();
        }

        /**
         * Записує модель у дерево моделей проєкту.
         * <p>
         * {@code installFunctionAttributes} робить класифікатор функційним і
         * заводить корінь дерева функцій, але переліку моделей не чіпає — у
         * застосунку цей рядок додає панель моделей після діалогу створення.
         * Без нього модель у проєкті є, а у вікні «Діаграми» порожньо, і
         * відкрити її нема через що.
         */
        private void registerInModelTree(Qualifier qualifier, String name) {
            Qualifier tree = IDEF0Plugin.getModelTree(engine);
            Element element = engine.createElement(tree.getId());
            engine.setAttribute(element,
                    StandardAttributesPlugin.getAttributeQualifierId(engine),
                    Long.valueOf(qualifier.getId()));
            engine.setAttribute(element, (Attribute) engine.getPluginProperty(
                    "Core", HierarchicalPlugin.HIERARHICAL_ATTRIBUTE),
                    new HierarchicalPersistent());
            engine.setAttribute(element,
                    StandardAttributesPlugin.getAttributeNameAttribute(engine),
                    name);
        }

        /**
         * Блоки й стрілки однієї діаграми. Діаграма — це декомпозиція
         * {@code owner}, тож і блоки створюються як його діти, і стрілки
         * прив'язуються до нього.
         */
        @SuppressWarnings("unchecked")
        private void children(Function owner, Map<String, Object> node) {
            Map<String, Function> named = new HashMap<String, Function>();

            for (Object item : list(node.get("blocks"))) {
                Map<String, Object> block = (Map<String, Object>) item;
                String name = text(block.get("name"), "");
                Function function = createFunction(owner, name, block);
                String id = text(block.get("id"), null);
                if (id != null)
                    named.put(id, function);
                children(function, block);
            }

            arrows(owner, node.get("arrows"), named);
        }

        /**
         * Стрілки однієї діаграми — двома проходами: скільки їх припадає на
         * кожен бік блока, видно лише коли зібрано всі. Від цього залежить,
         * як рознести точки кріплення, щоб вони не лягли одна на одну.
         */
        @SuppressWarnings("unchecked")
        private void arrows(Function diagram, Object described,
                            Map<String, Function> named) {
            List<Arrow> parsed = new ArrayList<Arrow>();
            Map<String, Integer> counts = new HashMap<String, Integer>();
            for (Object item : list(described)) {
                Arrow arrow = parse((Map<String, Object>) item, named);
                count(counts, arrow.from, arrow.fromSide);
                count(counts, arrow.to, arrow.toSide);
                parsed.add(arrow);
            }

            Map<String, Integer> seen = new HashMap<String, Integer>();
            for (Arrow arrow : parsed)
                draw(diagram, arrow, counts, seen);
        }

        private void count(Map<String, Integer> counts, Function block,
                           int side) {
            if (block == null)
                return;
            String key = key(block, side);
            Integer value = counts.get(key);
            counts.put(key, Integer.valueOf(value == null ? 1
                    : value.intValue() + 1));
        }

        private static String key(Function block, int side) {
            return System.identityHashCode(block) + ":" + side;
        }

        private Function createFunction(Function parent, String name,
                                        Map<String, Object> block) {
            Function function = plugin.createFunction(parent,
                    Function.TYPE_PROCESS);
            function.setName(name);
            if (block != null) {
                FRectangle bounds = function.getBounds();
                function.setBounds(new FRectangle(
                        number(block.get("x"), bounds.getX()),
                        number(block.get("y"), bounds.getY()),
                        number(block.get("width"), bounds.getWidth()),
                        number(block.get("height"), bounds.getHeight())));
                Color color = color(block.get("color"));
                if (color != null)
                    function.setBackground(color);
                Color foreground = color(block.get("text-color"));
                if (foreground != null)
                    function.setForeground(foreground);
            }
            blocks++;
            return function;
        }

        /**
         * Розбір опису стрілки: куди чіпляються кінці та якими сторонами.
         * <p>
         * У IDEF0 роль задає сторона: ліворуч — вхід, зверху — керування,
         * праворуч — вихід, знизу — механізм. Окремого поля «роль» у моделі
         * немає, тому опис і оперує сторонами.
         */
        private Arrow parse(Map<String, Object> arrow,
                            Map<String, Function> named) {
            Arrow result = new Arrow();
            result.name = text(arrow.get("name"), null);

            String from = text(arrow.get("from"), "border");
            String to = text(arrow.get("to"), "border");

            result.fromSide = side(arrow.get("from-side"), MovingPanel.RIGHT);
            result.toSide = side(arrow.get("to-side"), MovingPanel.LEFT);

            // Стрілка з межі діаграми заходить із того ж боку, з якого входить
            // у блок: вхід — зліва, керування — згори.
            if (isBorder(from) && arrow.get("from-side") == null)
                result.fromSide = result.toSide;
            if (isBorder(to) && arrow.get("to-side") == null)
                result.toSide = result.fromSide;

            result.color = color(arrow.get("color"));
            result.route = text(arrow.get("route"), null);
            if (result.route != null && !"under".equals(result.route)
                    && !"over".equals(result.route))
                throw new IllegalArgumentException("Стрілка " + result.name
                        + ": невідомий маршрут '" + result.route
                        + "' (under, over)");

            result.from = block(from, named, result.name);
            result.to = block(to, named, result.name);
            return result;
        }

        private Function block(String target, Map<String, Function> named,
                               String arrowName) {
            if (isBorder(target))
                return null;
            Function function = named.get(target);
            if (function == null)
                throw new IllegalArgumentException("Стрілка " + arrowName
                        + ": немає блока з id '" + target
                        + "' на цій діаграмі");
            return function;
        }

        private void draw(Function diagram, Arrow arrow,
                          Map<String, Integer> counts,
                          Map<String, Integer> seen) {
            double[] start = anchor(arrow.from, arrow.fromSide, counts, seen);
            double[] end = anchor(arrow.to, arrow.toSide, counts, seen);

            // Кінець на межі стоїть навпроти протилежного кінця, інакше
            // стрілка входила б у діаграму збоку від того, куди йде.
            if (arrow.from == null)
                start = borderAnchor(arrow.fromSide, end);
            if (arrow.to == null)
                end = borderAnchor(arrow.toSide, start);

            Sector sector = plugin.createSector();
            sector.setFunction(diagram);

            if (arrow.name != null)
                sector.setStream(stream(arrow.name), ReplaceStreamType.CHILDREN);

            border(sector.getStart(), arrow.from, arrow.fromSide);
            border(sector.getEnd(), arrow.to, arrow.toSide);

            List<double[]> path = arrow.route == null
                    ? route(start, arrow.fromSide, end, arrow.toSide)
                    : rail(start, arrow.fromSide, end, arrow.toSide,
                    rail(diagram, "under".equals(arrow.route)));
            sector.setSectorPointPersistents(points(path));

            if (arrow.color != null)
                colors.put(Long.valueOf(((NSector) sector).getElementId()),
                        arrow.color);

            arrows++;
        }

        /**
         * Точка кріплення на боці блока. Кілька стрілок на одному боці
         * розносяться рівномірно, як їх розкладає застосунок.
         */
        private double[] anchor(Function block, int side,
                                Map<String, Integer> counts,
                                Map<String, Integer> seen) {
            if (block == null)
                return null;
            String key = key(block, side);
            Integer index = seen.get(key);
            int i = index == null ? 0 : index.intValue();
            seen.put(key, Integer.valueOf(i + 1));
            int total = counts.get(key).intValue();

            FRectangle bounds = block.getBounds();
            double part = (i + 1.0) / (total + 1.0);
            switch (side) {
                case MovingPanel.LEFT:
                    return new double[]{bounds.getX(),
                            bounds.getY() + bounds.getHeight() * part};
                case MovingPanel.RIGHT:
                    return new double[]{bounds.getX() + bounds.getWidth(),
                            bounds.getY() + bounds.getHeight() * part};
                case MovingPanel.TOP:
                    return new double[]{
                            bounds.getX() + bounds.getWidth() * part,
                            bounds.getY()};
                default:
                    return new double[]{
                            bounds.getX() + bounds.getWidth() * part,
                            bounds.getY() + bounds.getHeight()};
            }
        }

        private static double[] borderAnchor(int side, double[] opposite) {
            switch (side) {
                case MovingPanel.LEFT:
                    return new double[]{0.0, opposite[1]};
                case MovingPanel.RIGHT:
                    return new double[]{CANVAS_WIDTH, opposite[1]};
                case MovingPanel.TOP:
                    return new double[]{opposite[0], 0.0};
                default:
                    return new double[]{opposite[0], CANVAS_HEIGHT};
            }
        }

        /**
         * Кінець стрілки: або на боці блока ({@code functionType}), або на
         * межі діаграми ({@code borderType}). Одне з двох, не обидва — тип
         * кінця рушій виводить саме з того, яке поле заповнене.
         */
        private void border(NSectorBorder border, Function block, int side) {
            if (block == null) {
                border.setFunctionA(null);
                border.setFunctionTypeA(-1);
                border.setBorderTypeA(side);
            } else {
                border.setFunctionA(block);
                border.setFunctionTypeA(side);
                border.setBorderTypeA(-1);
            }
            border.commit();
        }

        /**
         * Ламана між кінцями. Стрілки IDEF0 ортогональні, тож маршрут — це
         * два-три відрізки: вийти з боку блока перпендикулярно, дійти по
         * другій осі, увійти в другий кінець теж перпендикулярно.
         */
        private static List<double[]> route(double[] start, int fromSide,
                                            double[] end, int toSide) {
            boolean fromHorizontal = fromSide == MovingPanel.LEFT
                    || fromSide == MovingPanel.RIGHT;
            boolean toHorizontal = toSide == MovingPanel.LEFT
                    || toSide == MovingPanel.RIGHT;

            List<double[]> path = new ArrayList<double[]>();
            path.add(start);
            if (fromHorizontal && toHorizontal) {
                if (Math.abs(start[1] - end[1]) > 0.001) {
                    double middle = (start[0] + end[0]) / 2.0;
                    path.add(new double[]{middle, start[1]});
                    path.add(new double[]{middle, end[1]});
                }
            } else if (fromHorizontal) {
                path.add(new double[]{end[0], start[1]});
            } else if (toHorizontal) {
                path.add(new double[]{start[0], end[1]});
            } else if (Math.abs(start[0] - end[0]) > 0.001) {
                double middle = (start[1] + end[1]) / 2.0;
                path.add(new double[]{start[0], middle});
                path.add(new double[]{end[0], middle});
            }
            path.add(end);

            // Нульові відрізки збивають визначення напрямку: два сусідні кінці
            // з однаковими координатами не паралельні жодній осі.
            List<double[]> result = new ArrayList<double[]>(path.size());
            for (double[] point : path) {
                if (result.isEmpty())
                    result.add(point);
                else {
                    double[] last = result.get(result.size() - 1);
                    if (Math.abs(last[0] - point[0]) > 0.001
                            || Math.abs(last[1] - point[1]) > 0.001)
                        result.add(point);
                }
            }
            return result;
        }

        /**
         * Обхід усіх блоків діаграми знизу або згори.
         * <p>
         * Нотація вимагає саме цього для зворотних зв'язків: «down and under»
         * для зв'язку за входом, «up and over» — за керуванням. Прямий
         * маршрут тут не годиться, бо стрілка йде назад і пройшла б крізь
         * блоки, які щойно обійшла.
         */
        private static List<double[]> rail(double[] start, int fromSide,
                                           double[] end, int toSide,
                                           double railY) {
            List<double[]> path = new ArrayList<double[]>();
            path.add(start);
            double[] out = step(start, fromSide);
            double[] in = step(end, toSide);
            path.add(out);
            path.add(new double[]{out[0], railY});
            path.add(new double[]{in[0], railY});
            path.add(in);
            path.add(end);
            return path;
        }

        /** Відступ від сторони блока, щоб стрілка не йшла по його межі. */
        private static double[] step(double[] point, int side) {
            switch (side) {
                case MovingPanel.LEFT:
                    return new double[]{point[0] - RAIL_MARGIN, point[1]};
                case MovingPanel.RIGHT:
                    return new double[]{point[0] + RAIL_MARGIN, point[1]};
                case MovingPanel.TOP:
                    return new double[]{point[0], point[1] - RAIL_MARGIN};
                default:
                    return new double[]{point[0], point[1] + RAIL_MARGIN};
            }
        }

        /**
         * Висота смуги обходу: під найнижчим блоком діаграми або над
         * найвищим.
         */
        private static double rail(Function diagram, boolean under) {
            double result = under ? 0.0 : CANVAS_HEIGHT;
            for (int i = 0; i < diagram.getChildCount(); i++) {
                FRectangle bounds = ((Function) diagram.getChildAt(i))
                        .getBounds();
                double edge = under ? bounds.getY() + bounds.getHeight()
                        : bounds.getY();
                if (under ? edge > result : edge < result)
                    result = edge;
            }
            return under ? result + RAIL_MARGIN : result - RAIL_MARGIN;
        }

        /**
         * Ламана в те, що зберігає модель.
         * <p>
         * Напрямні тут суттєві, а не декоративні: орієнтацію відрізка
         * застосунок визначає не порівнянням координат, а тим, чи сидять його
         * кінці на спільній напрямній. Тож сусіди з однаковим {@code y} мусять
         * ділити напрямну по {@code y}, інакше горизонтальний відрізок
         * прочитається як вертикальний.
         */
        private List<SectorPointPersistent> points(List<double[]> path) {
            int size = path.size();
            long[] xOrdinates = new long[size];
            long[] yOrdinates = new long[size];

            for (int i = 0; i < size; i++) {
                xOrdinates[i] = IDEF0Plugin.getNextOrdinateId(engine);
                yOrdinates[i] = IDEF0Plugin.getNextOrdinateId(engine);
            }
            for (int i = 1; i < size; i++) {
                if (Math.abs(path.get(i)[1] - path.get(i - 1)[1]) < 0.001)
                    yOrdinates[i] = yOrdinates[i - 1];
                else if (Math.abs(path.get(i)[0] - path.get(i - 1)[0]) < 0.001)
                    xOrdinates[i] = xOrdinates[i - 1];
            }

            List<SectorPointPersistent> result = new ArrayList<SectorPointPersistent>(
                    size);
            for (int i = 0; i < size; i++) {
                SectorPointPersistent point = new SectorPointPersistent();
                point.setXOrdinateId(xOrdinates[i]);
                point.setYOrdinateId(yOrdinates[i]);
                point.setXPosition(path.get(i)[0]);
                point.setYPosition(path.get(i)[1]);
                point.setPosition(i);
                // Тип — орієнтація напрямної, що виходить із точки; в
                // останньої такої немає, тож вона повторює попередню.
                int neighbour = i + 1 < size ? i + 1 : i - 1;
                boolean horizontal = neighbour < 0
                        || Math.abs(path.get(neighbour)[1] - path.get(i)[1]) < 0.001;
                point.setPointType(horizontal ? 0 : 1);
                result.add(point);
            }
            return result;
        }

        /**
         * Проганяє кожну діаграму через ту саму розкладку, якою застосунок
         * малює її на екрані, і зберігає результат назад.
         * <p>
         * Це заміна кроку «відкрити в застосунку й зберегти», який
         * документація формату називає остаточною перевіркою. Тут він потрібен
         * не для перевірки, а по суті: рамка підпису стрілки рахується за
         * метриками шрифту, і порахувати її наперед означало б повторити
         * розкладку в генераторі — другою реалізацією, яка розійдеться з
         * першою.
         */
        private void normalize(Function function) {
            if (function.getChildCount() > 0) {
                MovingArea area = PIDEF0painter.createMovingArea(LAYOUT_SIZE,
                        plugin, function);
                area.setActiveFunction(function);
                
                SectorRefactor refactor = area.getRefactor();
                List<FRectangle> placed = new ArrayList<FRectangle>();
                for (int i = 0; i < refactor.getSectorsCount(); i++) {
                    PaintSector sector = refactor.getSector(i);
                    if (sector.getText() == null)
                        continue;
                    fit(area, sector.getText());
                    spread(sector, placed);
                }

                for (int i = 0; i < refactor.getSectorsCount(); i++) {
                    PaintSector sector = refactor.getSector(i);
                    Color color = colors.get(Long.valueOf(
                            ((NSector) sector.getSector()).getElementId()));
                    if (color != null) {
                        sector.setColor(color);
                        // Колір лягає у візуальний стан лише звідси:
                        // saveToFunction пише геометрію, а не вигляд.
                        sector.saveVisual();
                    }
                }
                refactor.saveToFunction();

                // saveToFunction ставить дати ревізії поточним часом — для
                // застосунку це правда, для генератора шум: два прогони на
                // незміненому описі дали б різні файли, і git показував би
                // зміну там, де моделі не чіпали. Дати лишаються порожніми;
                // якщо вони потрібні, їх дописують у файлах — правити
                // значення формат дозволяє.
                function.setRevDate(null);
                function.setSystemRevDate(null);
            }
            for (int i = 0; i < function.getChildCount(); i++)
                normalize((Function) function.getChildAt(i));
        }

        /**
         * Розводить підписи, що лягли один на одного.
         * <p>
         * Стрілки, які йдуть з межі до одного боку блока, мають однакову
         * довжину, тож і підписи сідають на одну висоту. Зсувати можна лише
         * вздовж стрілки — інакше підпис відірветься від своєї лінії, — тому
         * рухаються тільки підписи прямовисних стрілок.
         */
        private static void spread(PaintSector sector,
                                   List<FRectangle> placed) {
            FRectangle bounds = sector.getText().getBounds();
            if (vertical(sector))
                for (int step = 0; step < 12 && overlaps(bounds, placed); step++)
                    bounds.setY(bounds.getY() + bounds.getHeight() + 4.0);
            placed.add(new FRectangle(bounds));
        }

        /** Стрілка з двох точок на спільній вертикалі. */
        private static boolean vertical(PaintSector sector) {
            List<SectorPointPersistent> points = sector.getSector()
                    .getSectorPointPersistents();
            return points.size() == 2
                    && points.get(0).getXOrdinateId() == points.get(1)
                    .getXOrdinateId();
        }

        /**
         * Дотик рахується за накладання: два підписи, розділені одним
         * пікселем, читаються як один рядок.
         */
        private static boolean overlaps(FRectangle bounds,
                                        List<FRectangle> placed) {
            double gap = 8.0;
            for (FRectangle other : placed)
                if (bounds.getX() - gap < other.getX() + other.getWidth()
                        && other.getX() < bounds.getX() + bounds.getWidth() + gap
                        && bounds.getY() < other.getY() + other.getHeight()
                        && other.getY() < bounds.getY() + bounds.getHeight())
                    return true;
            return false;
        }

        /**
         * Ширина рамки підпису.
         * <p>
         * Без неї рамка лишається нульовою, і розкладка розганяє її до
         * мінімальних восьми пікселів — підпис ріжеться посеред слова.
         * Ширина — це весь напис в один рядок, але не ширше чверті діаграми
         * (та сама межа, що й у застосунку) і не вужче за найдовше слово,
         * інакше перенесення знову розірве слово.
         */
        private void fit(MovingArea area, MovingLabel label) {
            String text = label.getText();
            if (text == null || text.length() == 0) {
                label.resetBoundsX();
                return;
            }

            area.stringBounder.setFont(label.getFont());
            double width = area.stringBounder.getStringBounds(text).getWidth();
            if (width > CANVAS_WIDTH / 4)
                width = CANVAS_WIDTH / 4;
            for (String word : text.split("\\s+")) {
                double wordWidth = area.stringBounder.getStringBounds(word)
                        .getWidth();
                if (wordWidth > width)
                    width = wordWidth;
            }

            FRectangle bounds = label.getBounds();
            // Запас на округлення: рівно за розміром напис іноді переноситься.
            label.setBounds(new FRectangle(bounds.getX(), bounds.getY(),
                    width + 4.0, bounds.getHeight()));
            label.resetBoundsX();
        }

        private Stream stream(String name) {
            Stream stream = streams.get(name);
            if (stream == null) {
                stream = (Stream) plugin.createRow(plugin.getBaseStream(), true);
                stream.setName(name);
                streams.put(name, stream);
            }
            return stream;
        }
    }

    private static boolean isBorder(String target) {
        return target == null || "border".equals(target);
    }

    private static int side(Object value, int fallback) {
        if (value == null)
            return fallback;
        String name = value.toString().trim().toLowerCase();
        if ("input".equals(name) || "left".equals(name))
            return MovingPanel.LEFT;
        if ("control".equals(name) || "top".equals(name))
            return MovingPanel.TOP;
        if ("output".equals(name) || "right".equals(name))
            return MovingPanel.RIGHT;
        if ("mechanism".equals(name) || "bottom".equals(name))
            return MovingPanel.BOTTOM;
        throw new IllegalArgumentException("Невідома сторона: " + value
                + " (input, control, output, mechanism)");
    }

    /**
     * Іменованої константи для IDEF0 у {@link MovingArea} немає: нуль — це
     * типове значення {@code F_DECOMPOSITION_TYPE}, а іменовані є лише для
     * двох відхилень від нього.
     */
    private static final int DIAGRAM_TYPE_IDEF0 = 0;

    private static int decompositionType(Object value) {
        if (value == null)
            return DIAGRAM_TYPE_IDEF0;
        String name = value.toString().trim().toLowerCase();
        if ("idef0".equals(name))
            return DIAGRAM_TYPE_IDEF0;
        if ("dfd".equals(name))
            return MovingArea.DIAGRAM_TYPE_DFD;
        if ("dfds".equals(name))
            return MovingArea.DIAGRAM_TYPE_DFDS;
        throw new IllegalArgumentException("Невідомий тип діаграми: " + value
                + " (idef0, dfd, dfds)");
    }

    private static Color color(Object value) {
        if (value == null)
            return null;
        String text = value.toString().trim();
        if (text.startsWith("#"))
            text = text.substring(1);
        if (text.length() != 6)
            throw new IllegalArgumentException("Колір очікується як '#rrggbb': "
                    + value);
        return new Color(Integer.parseInt(text, 16));
    }

    private static List<?> list(Object value) {
        if (value == null)
            return new ArrayList<Object>();
        if (!(value instanceof List))
            throw new IllegalArgumentException("Очікується список: " + value);
        return (List<?>) value;
    }

    private static double number(Object value, double fallback) {
        if (value == null)
            return fallback;
        if (value instanceof Number)
            return ((Number) value).doubleValue();
        return Double.parseDouble(value.toString());
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }
}
