package works.repka.rysteams.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import works.repka.ryscore.migrate.Migrations;
import works.repka.rysteams.RYSTeams;

/**
 * Шаг переезда {@code teams} (порядок 10) в реестре ядра: обёртка над {@link FtbImport#merge}. Идёт сам один раз по флагу
 * сборки {@code config/ryscore/migrate.json} или командой {@code /rys migrate run teams}; копию FTB, отметку и отчёт
 * {@code [rys-migrate]} делает ядро.
 *
 * <p>Нужен, пока в мире есть команды FTB и отметки нет. Загруженный FTB Teams шаг не останавливает: FTB Chunks держит его в
 * сборке до карты и привата, а команды нужны семье сразу. Повтор безопасен: уже перенесённые команды пропускаются, на мире,
 * где команды переносили руками (сервер RepYS), шаг ничего не меняет и ставит отметку.
 */
public final class TeamsMigration implements Migrations.Step {
    public static final String ID = "teams";

    /** Где шаг берёт хранилище и как его пишет; проверки подменяют. */
    public interface Storage {
        TeamStore store(Migrations.Context c);

        /** Записать хранилище на диск сейчас: отметка ядра ставится только после записи. */
        void save(Migrations.Context c, TeamStore store) throws IOException;
    }

    private final Storage storage;

    public TeamsMigration() {
        this(new ServerStorage());
    }

    public TeamsMigration(Storage storage) {
        this.storage = Objects.requireNonNull(storage);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Migrations.Side side() {
        return Migrations.Side.SERVER;
    }

    @Override
    public String modId() {
        return RYSTeams.MODID;
    }

    @Override
    public boolean needed(Migrations.Context c) {
        return FtbImport.hasData(c.worldDir());
    }

    @Override
    public Migrations.Report run(Migrations.Context c) throws Exception {
        Path world = Objects.requireNonNull(c.worldDir(), "no world folder");
        TeamStore store = storage.store(c);
        Migrations.Report report = Migrations.Report.create();
        if (store.ftbImported) report.line(Say.t("already_run", "the teams were already migrated by command: reconciliation only"));
        if (c.isLoaded("ftbteams")) report.line(Say.t("ftb_still", "FTB Teams is still installed: teams created in it after the migration are not migrated"));
        FtbImport.Result result = FtbImport.merge(world, store, c.server(), report);
        storage.save(c, store);
        TeamService.imported();
        TeamService.allyOffers(result.offers());
        return report;
    }

    /** Хранилище мира и запись только своего файла {@code data/rysteams.dat}. */
    static final class ServerStorage implements Storage {
        @Override
        public TeamStore store(Migrations.Context c) {
            return TeamStore.get(server(c));
        }

        @Override
        public void save(Migrations.Context c, TeamStore store) throws IOException {
            // SavedData.save глотает ошибку записи и пишет в рабочем потоке: здесь пишем сами и сразу, сбой роняет шаг
            MinecraftServer server = server(c);
            Path file = Objects.requireNonNull(c.worldDir()).resolve("data").resolve(TeamStore.NAME + ".dat");
            CompoundTag root = new CompoundTag();
            root.put("data", store.save(new CompoundTag(), server.registryAccess()));
            NbtUtils.addCurrentDataVersion(root);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            // хранилище остаётся помеченным: обычное сохранение мира запишет его ещё раз
            store.setDirty();
        }

        private static MinecraftServer server(Migrations.Context c) {
            return Objects.requireNonNull(c.server(), "the teams step runs only on the server");
        }
    }
}
