package com.ramussoft.demo;

import java.io.File;

import com.ramussoft.common.Engine;
import com.ramussoft.core.format.ProjectReader;
import com.ramussoft.core.impl.FileIEngineImpl;
import com.ramussoft.database.FileDatabaseFactory;
import com.ramussoft.database.MemoryDatabase;

/**
 * Складає {@code .rsf} з дерева YAML-файлів — зворотний бік
 * {@link RsfToYaml}.
 * <p>
 * Відповідність «стабільний ідентифікатор ↔ числовий ключ» зберігається в
 * самому проєкті, тому наступний експорт дасть ті самі файли.
 *
 * <pre>
 * java -cp ... com.ramussoft.demo.YamlToRsf каталог проєкт.rsf
 * </pre>
 */
public final class YamlToRsf {

    private YamlToRsf() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Використання: YamlToRsf <каталог> <файл.rsf>");
            System.exit(2);
            return;
        }

        File source = new File(args[0]);
        if (!source.isDirectory()) {
            System.err.println("Не знайдено каталог: " + source);
            System.exit(2);
            return;
        }
        File target = new File(args[1]);

        MemoryDatabase database = (MemoryDatabase) FileDatabaseFactory
                .createDatabase();
        try {
            Engine engine = database.getEngine(null);
            new ProjectReader(engine).read(source);
            ((FileIEngineImpl) engine.getDeligate()).saveToFile(target);
        } finally {
            database.close();
        }

        System.out.println("Записано " + target.getAbsolutePath());
    }
}
