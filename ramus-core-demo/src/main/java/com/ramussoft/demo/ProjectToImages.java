package com.ramussoft.demo;

import java.awt.Dimension;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.ramussoft.common.AccessRules;
import com.ramussoft.common.Engine;
import com.ramussoft.common.Qualifier;
import com.ramussoft.core.impl.FileIEngineImpl;
import com.ramussoft.database.FileDatabaseFactory;
import com.ramussoft.database.MemoryDatabase;
import com.ramussoft.idef0.IDEF0Plugin;
import com.ramussoft.idef0.NDataPluginFactory;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.print.PIDEF0painter;

/**
 * Малює всі діаграми проєкту у файли, без екрана.
 * <p>
 * Друга половина docs-as-code: модель лежить у git текстом, а картинка для
 * документації збирається з неї на CI тим самим кодом, яким малює застосунок
 * ({@link PIDEF0painter}). Відмальовування детерміноване, тож повторний запуск
 * на незміненій моделі дає ті самі файли.
 *
 * <pre>
 * java -cp ... com.ramussoft.demo.ProjectToImages &lt;проєкт&gt; &lt;каталог&gt; [png|svg] [ШxВ]
 * </pre>
 * <p>
 * Приймає і каталог проєкту, і {@code .rsf} — усе, що відкриває
 * {@link FileDatabaseFactory}.
 */
public final class ProjectToImages {

    /**
     * Той самий розмір, що й в еталонних знімках {@code storage-test}:
     * масштаб діаграми рахується від нього, тож він і задає різкість.
     */
    private static final Dimension DEFAULT_SIZE = new Dimension(1200, 900);

    private ProjectToImages() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) {
            System.err.println("Використання: ProjectToImages <проєкт>"
                    + " <каталог> [png|svg] [ШИРИНАxВИСОТА]");
            System.exit(2);
            return;
        }

        File source = new File(args[0]);
        if (!source.exists()) {
            System.err.println("Не знайдено: " + source);
            System.exit(2);
            return;
        }
        File target = new File(args[1]);

        String extension = args.length > 2 ? args[2].toLowerCase() : "png";
        int format;
        if ("png".equals(extension))
            format = PIDEF0painter.PNG_FORMAT;
        else if ("svg".equals(extension))
            format = PIDEF0painter.SVG_FORMAT;
        else {
            System.err.println("Невідомий формат: " + extension
                    + " (очікується png або svg)");
            System.exit(2);
            return;
        }

        Dimension size = args.length > 3 ? parseSize(args[3]) : DEFAULT_SIZE;
        if (size == null) {
            System.err.println("Не розібрано розмір: " + args[3]
                    + " (очікується, наприклад, 1600x1200)");
            System.exit(2);
            return;
        }

        // Малювання йде через Swing-класи застосунку, але вікна не потребує.
        System.setProperty("java.awt.headless", "true");

        target.mkdirs();
        int count = 0;

        MemoryDatabase database = (MemoryDatabase) FileDatabaseFactory
                .createDatabase(source);
        try {
            Engine engine = database.getEngine(null);
            AccessRules rules = database.getAccessRules(null);

            List<Qualifier> models = new ArrayList<Qualifier>(
                    IDEF0Plugin.getBaseQualifiers(engine));
            // Порядок обходу не має залежати від того, як лягли класифікатори
            // в пам'яті: інакше два запуски дадуть різні імена файлів.
            Collections.sort(models, new Comparator<Qualifier>() {
                @Override
                public int compare(Qualifier a, Qualifier b) {
                    return a.getName().compareTo(b.getName());
                }
            });

            for (Qualifier model : models) {
                DataPlugin plugin = NDataPluginFactory.getDataPlugin(model,
                        engine, rules);
                Function base = plugin.getBaseFunction();
                if (base == null)
                    continue;
                File directory = models.size() > 1 ? new File(target,
                        safeName(model.getName())) : target;
                // Корінь дерева функцій — контекстна діаграма A-0: на ній
                // єдиний блок, який далі розкривається в A0.
                count += write(plugin, base, directory, "A-0", null, size,
                        format, extension);
            }

            ((FileIEngineImpl) engine.getDeligate()).close();
        } finally {
            database.close();
        }

        System.out.println("Записано діаграм: " + count + " у "
                + target.getAbsolutePath());
    }

    /**
     * Малює діаграму декомпозиції функції та спускається в дочірні.
     * <p>
     * Функція без дітей власної діаграми не має — малювати її означало б
     * покласти в каталог порожній аркуш на кожен блок моделі.
     *
     * @param number номер вузла IDEF0 ({@code A-0}, {@code A0}, {@code A12}) —
     *               він і дає ім'я файлу, бо назви блоків у моделі можуть
     *               повторюватись
     * @param digits цифрова частина номера без літери: {@code null} на
     *               контекстній діаграмі, {@code ""} на A0, далі {@code "1"},
     *               {@code "12"}. Потрібна окремо, бо діти A0 — це A1, A2, а
     *               не A01, A02.
     * @return скільки файлів записано
     */
    private static int write(DataPlugin plugin, Function function,
                             File directory, String number, String digits,
                             Dimension size, int format, String extension)
            throws Exception {
        int count = 0;
        if (function.getChildCount() > 0) {
            directory.mkdirs();
            File file = new File(directory, number + " "
                    + safeName(function.getName()) + "." + extension);
            PIDEF0painter painter = new PIDEF0painter(function, size, plugin);
            OutputStream out = new FileOutputStream(file);
            try {
                painter.writeToStream(out, format);
            } finally {
                out.close();
            }
            System.out.println("  " + file.getName());
            count++;
        }
        for (int i = 0; i < function.getChildCount(); i++) {
            String childDigits;
            String childNumber;
            if (digits == null) {
                // Діти контекстної діаграми — це вершина моделі, A0.
                childDigits = "";
                childNumber = "A0";
            } else {
                childDigits = digits + (i + 1);
                childNumber = "A" + childDigits;
            }
            count += write(plugin, (Function) function.getChildAt(i),
                    directory, childNumber, childDigits, size, format,
                    extension);
        }
        return count;
    }

    private static Dimension parseSize(String text) {
        int index = text.toLowerCase().indexOf('x');
        if (index <= 0)
            return null;
        try {
            int width = Integer.parseInt(text.substring(0, index).trim());
            int height = Integer.parseInt(text.substring(index + 1).trim());
            if (width <= 0 || height <= 0)
                return null;
            return new Dimension(width, height);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Назва блока стає частиною імені файлу, а в ній трапляється будь-що —
     * від скісної риски до перекладу рядка.
     */
    private static String safeName(String name) {
        if (name == null)
            return "";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append("\\/:*?\"<>|".indexOf(c) >= 0 || c < ' ' ? '-' : c);
        }
        return sb.toString().trim();
    }
}
