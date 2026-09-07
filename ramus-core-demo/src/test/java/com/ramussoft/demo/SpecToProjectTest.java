package com.ramussoft.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ramussoft.common.Engine;
import com.ramussoft.core.impl.FileIEngineImpl;
import com.ramussoft.database.FileDatabaseFactory;
import com.ramussoft.database.MemoryDatabase;
import com.ramussoft.idef0.IDEF0Plugin;
import com.ramussoft.idef0.NDataPluginFactory;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;

/**
 * Опис → проєкт → картинка.
 * <p>
 * Перевіряється не те, що код відпрацював, а що модель вийшла справжньою:
 * блоки стали деревом функцій, стрілки — секторами з геометрією, а проєкт
 * відкривається тим самим шляхом, що й будь-який інший.
 */
public class SpecToProjectTest {

    private static final String SPEC = ""
            + "model: 'Обробка замовлення'\n"
            + "name: 'Обробити замовлення клієнта'\n"
            + "blocks:\n"
            + "- id: 'accept'\n"
            + "  name: 'Прийняти замовлення'\n"
            + "  x: 90\n"
            + "  y: 70\n"
            + "  width: 150\n"
            + "  height: 90\n"
            + "- id: 'pack'\n"
            + "  name: 'Скомплектувати'\n"
            + "  x: 330\n"
            + "  y: 220\n"
            + "  width: 150\n"
            + "  height: 90\n"
            + "  blocks:\n"
            + "  - id: 'pick'\n"
            + "    name: 'Зібрати позиції'\n"
            + "    x: 110\n"
            + "    y: 90\n"
            + "    width: 150\n"
            + "    height: 90\n"
            + "arrows:\n"
            + "- name: 'Замовлення клієнта'\n"
            + "  from: 'border'\n"
            + "  to: 'accept'\n"
            + "- name: 'Підтверджене замовлення'\n"
            + "  from: 'accept'\n"
            + "  to: 'pack'\n";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File project;

    @Before
    public void setUp() throws Exception {
        // Сесії рушія не мають лізти в домівку того, хто запустив тест.
        File home = folder.newFolder("home");
        System.setProperty("user.home", home.getAbsolutePath());
        System.setProperty("user.ramus.application.name", "RamusSpecTest");
        System.setProperty("java.awt.headless", "true");

        File spec = new File(folder.getRoot(), "spec.yaml");
        Writer writer = new OutputStreamWriter(new FileOutputStream(spec),
                "UTF-8");
        try {
            writer.write(SPEC);
        } finally {
            writer.close();
        }

        project = new File(folder.newFolder("out"), "Замовлення.ramus");
        SpecToProject.main(new String[]{spec.getAbsolutePath(),
                project.getAbsolutePath()});
    }

    @Test
    public void blocksBecomeFunctionTree() throws Exception {
        MemoryDatabase database = (MemoryDatabase) FileDatabaseFactory
                .createDatabase(project);
        try {
            Engine engine = database.getEngine(null);
            DataPlugin plugin = NDataPluginFactory.getDataPlugin(
                    IDEF0Plugin.getBaseQualifiers(engine).get(0), engine,
                    database.getAccessRules(null));

            Function base = plugin.getBaseFunction();
            assertEquals("на контекстній діаграмі має бути один блок", 1,
                    base.getChildCount());

            Function top = (Function) base.getChildAt(0);
            assertEquals("Обробити замовлення клієнта", top.getName());
            assertEquals("блоки діаграми A0", 2, top.getChildCount());

            List<String> names = new ArrayList<String>();
            for (int i = 0; i < top.getChildCount(); i++)
                names.add(((Function) top.getChildAt(i)).getName());
            assertEquals(
                    Arrays.asList("Прийняти замовлення", "Скомплектувати"),
                    names);

            Function pack = (Function) top.getChildAt(1);
            assertEquals("декомпозиція не створилась", 1,
                    pack.getChildCount());
            assertEquals("Зібрати позиції",
                    ((Function) pack.getChildAt(0)).getName());

            ((FileIEngineImpl) engine.getDeligate()).close();
        } finally {
            database.close();
        }
    }

    /**
     * Стрілка без геометрії — це стрілка, якої не намалювати: розкладка
     * будує ламану з точок, і на порожньому списку падає.
     */
    @Test
    public void arrowsCarryGeometry() throws Exception {
        MemoryDatabase database = (MemoryDatabase) FileDatabaseFactory
                .createDatabase(project);
        try {
            Engine engine = database.getEngine(null);
            DataPlugin plugin = NDataPluginFactory.getDataPlugin(
                    IDEF0Plugin.getBaseQualifiers(engine).get(0), engine,
                    database.getAccessRules(null));
            Function top = (Function) plugin.getBaseFunction().getChildAt(0);

            assertEquals("стрілки діаграми A0", 2, top.getSectors().size());
            for (Object item : top.getSectors()) {
                com.ramussoft.pb.Sector sector = (com.ramussoft.pb.Sector) item;
                assertTrue("стрілка без точок: " + sector.getName(), sector
                        .getSectorPointPersistents().size() >= 2);
            }

            ((FileIEngineImpl) engine.getDeligate()).close();
        } finally {
            database.close();
        }
    }

    /**
     * Той самий опис має давати той самий проєкт: інакше перезбирання моделі
     * показувало б зміну в git там, де опис не чіпали.
     */
    @Test
    public void sameSpecGivesSameProject() throws Exception {
        File spec = new File(folder.getRoot(), "spec.yaml");
        File again = new File(folder.newFolder("again"), "Замовлення.ramus");
        SpecToProject.main(new String[]{spec.getAbsolutePath(),
                again.getAbsolutePath()});

        List<String> differences = new ArrayList<String>();
        compare(project, again, "", differences);
        assertEquals("повторна збірка дала інші файли: " + differences, 0,
                differences.size());
    }

    /**
     * Порівняння версійованої частини: {@code .local} — стан інтерфейсу, він
     * навмисно поза порівнянням, як і поза сховищем версій.
     */
    private static void compare(File left, File right, String path,
                                List<String> differences) throws Exception {
        String[] names = left.list();
        if (names == null) {
            if (left.length() != right.length()) {
                differences.add(path);
                return;
            }
            byte[] a = read(left);
            byte[] b = read(right);
            if (!Arrays.equals(a, b))
                differences.add(path);
            return;
        }
        Arrays.sort(names);
        for (String name : names) {
            if (".local".equals(name))
                continue;
            File other = new File(right, name);
            if (!other.exists()) {
                differences.add(path + "/" + name);
                continue;
            }
            compare(new File(left, name), other, path + "/" + name,
                    differences);
        }
    }

    private static byte[] read(File file) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0)
                out.write(buffer, 0, count);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
