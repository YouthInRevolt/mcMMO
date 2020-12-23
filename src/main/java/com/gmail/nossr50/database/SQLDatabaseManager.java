package com.gmail.nossr50.database;

import com.gmail.nossr50.config.Config;
import com.gmail.nossr50.datatypes.database.DatabaseType;
import com.gmail.nossr50.datatypes.database.PlayerStat;
import com.gmail.nossr50.datatypes.database.UpgradeType;
import com.gmail.nossr50.datatypes.player.*;
import com.gmail.nossr50.datatypes.skills.CoreSkills;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.datatypes.skills.SuperAbilityType;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.runnables.database.UUIDUpdateAsyncTask;
import com.gmail.nossr50.util.Misc;
import com.gmail.nossr50.util.skills.SkillUtils;
import com.neetgames.mcmmo.MobHealthBarType;
import com.neetgames.mcmmo.UniqueDataType;
import com.neetgames.mcmmo.exceptions.InvalidSkillException;
import com.neetgames.mcmmo.exceptions.ProfileRetrievalException;
import com.neetgames.mcmmo.player.MMOPlayerData;
import com.neetgames.mcmmo.skill.RootSkill;
import com.neetgames.mcmmo.skill.SkillBossBarState;
import com.neetgames.mcmmo.skill.SuperSkill;
import org.apache.commons.lang.NullArgumentException;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public final class SQLDatabaseManager extends AbstractDatabaseManager {
    private static final String ALL_QUERY_VERSION = "total";
    private final String tablePrefix = Config.getInstance().getMySQLTablePrefix();

    private final Map<UUID, Integer> cachedUserIDs = new HashMap<>();

    private DataSource miscPool;
    private DataSource loadPool;
    private DataSource savePool;

    private boolean debug = false;

    private final ReentrantLock massUpdateLock = new ReentrantLock();

    protected SQLDatabaseManager() {
        String connectionString = "jdbc:mysql://" + Config.getInstance().getMySQLServerName()
                + ":" + Config.getInstance().getMySQLServerPort() + "/" + Config.getInstance().getMySQLDatabaseName();

        if(Config.getInstance().getMySQLSSL())
            connectionString +=
                    "?verifyServerCertificate=false"+
                    "&useSSL=true"+
                    "&requireSSL=true";
        else
            connectionString+=
                    "?useSSL=false";

        try {
            // Force driver to load if not yet loaded
            Class.forName("com.mysql.jdbc.Driver");
        }
        catch (ClassNotFoundException e) {
            e.printStackTrace();
            return;
            //throw e; // aborts onEnable()  Riking if you want to do this, fully implement it.
        }

        debug = Config.getInstance().getMySQLDebug();


        PoolProperties poolProperties = new PoolProperties();
        poolProperties.setDriverClassName("com.mysql.jdbc.Driver");
        poolProperties.setUrl(connectionString);
        poolProperties.setUsername(Config.getInstance().getMySQLUserName());
        poolProperties.setPassword(Config.getInstance().getMySQLUserPassword());
        poolProperties.setMaxIdle(Config.getInstance().getMySQLMaxPoolSize(PoolIdentifier.MISC));
        poolProperties.setMaxActive(Config.getInstance().getMySQLMaxConnections(PoolIdentifier.MISC));
        poolProperties.setInitialSize(0);
        poolProperties.setMaxWait(-1);
        poolProperties.setRemoveAbandoned(true);
        poolProperties.setRemoveAbandonedTimeout(60);
        poolProperties.setTestOnBorrow(true);
        poolProperties.setValidationQuery("SELECT 1");
        poolProperties.setValidationInterval(30000);
        miscPool = new DataSource(poolProperties);
        poolProperties = new PoolProperties();
        poolProperties.setDriverClassName("com.mysql.jdbc.Driver");
        poolProperties.setUrl(connectionString);
        poolProperties.setUsername(Config.getInstance().getMySQLUserName());
        poolProperties.setPassword(Config.getInstance().getMySQLUserPassword());
        poolProperties.setInitialSize(0);
        poolProperties.setMaxIdle(Config.getInstance().getMySQLMaxPoolSize(PoolIdentifier.SAVE));
        poolProperties.setMaxActive(Config.getInstance().getMySQLMaxConnections(PoolIdentifier.SAVE));
        poolProperties.setMaxWait(-1);
        poolProperties.setRemoveAbandoned(true);
        poolProperties.setRemoveAbandonedTimeout(60);
        poolProperties.setTestOnBorrow(true);
        poolProperties.setValidationQuery("SELECT 1");
        poolProperties.setValidationInterval(30000);
        savePool = new DataSource(poolProperties);
        poolProperties = new PoolProperties();
        poolProperties.setDriverClassName("com.mysql.jdbc.Driver");
        poolProperties.setUrl(connectionString);
        poolProperties.setUsername(Config.getInstance().getMySQLUserName());
        poolProperties.setPassword(Config.getInstance().getMySQLUserPassword());
        poolProperties.setInitialSize(0);
        poolProperties.setMaxIdle(Config.getInstance().getMySQLMaxPoolSize(PoolIdentifier.LOAD));
        poolProperties.setMaxActive(Config.getInstance().getMySQLMaxConnections(PoolIdentifier.LOAD));
        poolProperties.setMaxWait(-1);
        poolProperties.setRemoveAbandoned(true);
        poolProperties.setRemoveAbandonedTimeout(60);
        poolProperties.setTestOnBorrow(true);
        poolProperties.setValidationQuery("SELECT 1");
        poolProperties.setValidationInterval(30000);
        loadPool = new DataSource(poolProperties);

        checkStructure();
    }

    public void purgePowerlessUsers() {
        massUpdateLock.lock();
        mcMMO.p.getLogger().info("Purging powerless users...");

        Connection connection = null;
        Statement statement = null;
        int purged = 0;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.createStatement();

            purged = statement.executeUpdate("DELETE FROM " + tablePrefix + "skills WHERE "
                    + "taming = 0 AND mining = 0 AND woodcutting = 0 AND repair = 0 "
                    + "AND unarmed = 0 AND herbalism = 0 AND excavation = 0 AND "
                    + "archery = 0 AND swords = 0 AND axes = 0 AND acrobatics = 0 "
                    + "AND fishing = 0 AND alchemy = 0 AND tridents = 0 AND crossbows = 0;");

            statement.executeUpdate("DELETE FROM `" + tablePrefix + "experience` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "skills` `s` WHERE `" + tablePrefix + "experience`.`user_id` = `s`.`user_id`)");
            statement.executeUpdate("DELETE FROM `" + tablePrefix + "huds` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "skills` `s` WHERE `" + tablePrefix + "huds`.`user_id` = `s`.`user_id`)");
            statement.executeUpdate("DELETE FROM `" + tablePrefix + "cooldowns` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "skills` `s` WHERE `" + tablePrefix + "cooldowns`.`user_id` = `s`.`user_id`)");
            statement.executeUpdate("DELETE FROM `" + tablePrefix + "users` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "skills` `s` WHERE `" + tablePrefix + "users`.`id` = `s`.`user_id`)");
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
            massUpdateLock.unlock();
        }

        mcMMO.p.getLogger().info("Purged " + purged + " users from the database.");
    }

    public void purgeOldUsers() {
        massUpdateLock.lock();
        mcMMO.p.getLogger().info("Purging inactive users older than " + (PURGE_TIME / 2630000000L) + " months...");

        Connection connection = null;
        Statement statement = null;
        int purged = 0;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.createStatement();

            purged = statement.executeUpdate("DELETE FROM u, e, h, s, c USING " + tablePrefix + "users u " +
                    "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) " +
                    "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) " +
                    "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) " +
                    "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) " +
                    "WHERE ((UNIX_TIMESTAMP() - lastlogin) > " + PURGE_TIME + ")");
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
            massUpdateLock.unlock();
        }

        mcMMO.p.getLogger().info("Purged " + purged + " users from the database.");
    }

    public boolean removeUser(@NotNull String playerName, @Nullable UUID uuid) {
        boolean success = false;
        Connection connection = null;
        PreparedStatement statement = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("DELETE FROM u, e, h, s, c " +
                    "USING " + tablePrefix + "users u " +
                    "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) " +
                    "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) " +
                    "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) " +
                    "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) " +
                    "WHERE u.user = ?");

            statement.setString(1, playerName);

            success = statement.executeUpdate() != 0;
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }

        if (success) {
            if(uuid != null)
                cleanupUser(uuid);

            Misc.profileCleanup(playerName);
        }

        return success;
    }

    public void cleanupUser(UUID uuid) {
        cachedUserIDs.remove(uuid);
    }

    public boolean saveUser(@NotNull MMODataSnapshot dataSnapshot) {
        boolean success = true;
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.SAVE);

            int id = getUserID(connection, dataSnapshot.getPlayerName(), dataSnapshot.getPlayerUUID());

//            if (id == -1) {
//                id = newUser(connection, dataSnapshot.getPlayerName(), dataSnapshot.getPlayerUUID());
//                if (id == -1) {
//                    mcMMO.p.getLogger().severe("Failed to create new account for " + dataSnapshot.getPlayerName());
//                    return false;
//                }
//            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "users SET lastlogin = UNIX_TIMESTAMP() WHERE id = ?");
            statement.setInt(1, id);
            success &= (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                mcMMO.p.getLogger().severe("Failed to update last login for " + dataSnapshot.getPlayerName());
                return false;
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "skills SET "
                    + " taming = ?, mining = ?, repair = ?, woodcutting = ?"
                    + ", unarmed = ?, herbalism = ?, excavation = ?"
                    + ", archery = ?, swords = ?, axes = ?, acrobatics = ?"
                    + ", fishing = ?, alchemy = ?, tridents = ?, crossbows = ?, total = ? WHERE user_id = ?");
            statement.setInt(1, dataSnapshot.getSkillLevel(CoreSkills.TAMING_CS));
            statement.setInt(2, dataSnapshot.getSkillLevel(CoreSkills.MINING_CS));
            statement.setInt(3, dataSnapshot.getSkillLevel(CoreSkills.REPAIR_CS));
            statement.setInt(4, dataSnapshot.getSkillLevel(CoreSkills.WOODCUTTING_CS));
            statement.setInt(5, dataSnapshot.getSkillLevel(CoreSkills.UNARMED_CS));
            statement.setInt(6, dataSnapshot.getSkillLevel(CoreSkills.HERBALISM_CS));
            statement.setInt(7, dataSnapshot.getSkillLevel(CoreSkills.EXCAVATION_CS));
            statement.setInt(8, dataSnapshot.getSkillLevel(CoreSkills.ARCHERY_CS));
            statement.setInt(9, dataSnapshot.getSkillLevel(CoreSkills.SWORDS_CS));
            statement.setInt(10, dataSnapshot.getSkillLevel(CoreSkills.AXES_CS));
            statement.setInt(11, dataSnapshot.getSkillLevel(CoreSkills.ACROBATICS_CS));
            statement.setInt(12, dataSnapshot.getSkillLevel(CoreSkills.FISHING_CS));
            statement.setInt(13, dataSnapshot.getSkillLevel(CoreSkills.ALCHEMY_CS));
            statement.setInt(14, dataSnapshot.getSkillLevel(CoreSkills.TRIDENTS_CS));
            statement.setInt(15, dataSnapshot.getSkillLevel(CoreSkills.CROSSBOWS_CS));
            int total = 0;
            for (RootSkill rootSkill : CoreSkills.getNonChildSkills())
                total += dataSnapshot.getSkillLevel(rootSkill);
            statement.setInt(16, total);
            statement.setInt(17, id);
            success &= (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                mcMMO.p.getLogger().severe("Failed to update skills for " + dataSnapshot.getPlayerName());
                return false;
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "experience SET "
                    + " taming = ?, mining = ?, repair = ?, woodcutting = ?"
                    + ", unarmed = ?, herbalism = ?, excavation = ?"
                    + ", archery = ?, swords = ?, axes = ?, acrobatics = ?"
                    + ", fishing = ?, alchemy = ?, tridents = ?, crossbows = ?, WHERE user_id = ?");
            statement.setInt(1, dataSnapshot.getSkillXpLevel(CoreSkills.TAMING_CS));
            statement.setInt(2, dataSnapshot.getSkillXpLevel(CoreSkills.MINING_CS));
            statement.setInt(3, dataSnapshot.getSkillXpLevel(CoreSkills.REPAIR_CS));
            statement.setInt(4, dataSnapshot.getSkillXpLevel(CoreSkills.WOODCUTTING_CS));
            statement.setInt(5, dataSnapshot.getSkillXpLevel(CoreSkills.UNARMED_CS));
            statement.setInt(6, dataSnapshot.getSkillXpLevel(CoreSkills.HERBALISM_CS));
            statement.setInt(7, dataSnapshot.getSkillXpLevel(CoreSkills.EXCAVATION_CS));
            statement.setInt(8, dataSnapshot.getSkillXpLevel(CoreSkills.ARCHERY_CS));
            statement.setInt(9, dataSnapshot.getSkillXpLevel(CoreSkills.SWORDS_CS));
            statement.setInt(10, dataSnapshot.getSkillXpLevel(CoreSkills.AXES_CS));
            statement.setInt(11, dataSnapshot.getSkillXpLevel(CoreSkills.ACROBATICS_CS));
            statement.setInt(12, dataSnapshot.getSkillXpLevel(CoreSkills.FISHING_CS));
            statement.setInt(13, dataSnapshot.getSkillXpLevel(CoreSkills.ALCHEMY_CS));
            statement.setInt(14, dataSnapshot.getSkillXpLevel(CoreSkills.TRIDENTS_CS));
            statement.setInt(15, dataSnapshot.getSkillXpLevel(CoreSkills.CROSSBOWS_CS));
            statement.setInt(16, id);
            success &= (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                mcMMO.p.getLogger().severe("Failed to update experience for " + dataSnapshot.getPlayerName());
                return false;
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "cooldowns SET "
                    + "  mining = ?, woodcutting = ?, unarmed = ?"
                    + ", herbalism = ?, excavation = ?, swords = ?"
                    + ", axes = ?, blast_mining = ?, chimaera_wing = ?, archery = ?, tridents = ?, crossbows = ? WHERE user_id = ?");
            statement.setLong(1, dataSnapshot.getAbilityDATS(SuperAbilityType.SUPER_BREAKER));
            statement.setLong(2, dataSnapshot.getAbilityDATS(SuperAbilityType.TREE_FELLER));
            statement.setLong(3, dataSnapshot.getAbilityDATS(SuperAbilityType.BERSERK));
            statement.setLong(4, dataSnapshot.getAbilityDATS(SuperAbilityType.GREEN_TERRA));
            statement.setLong(5, dataSnapshot.getAbilityDATS(SuperAbilityType.GIGA_DRILL_BREAKER));
            statement.setLong(6, dataSnapshot.getAbilityDATS(SuperAbilityType.SERRATED_STRIKES));
            statement.setLong(7, dataSnapshot.getAbilityDATS(SuperAbilityType.SKULL_SPLITTER));
            statement.setLong(8, dataSnapshot.getAbilityDATS(SuperAbilityType.BLAST_MINING));
            statement.setLong(9, dataSnapshot.getUniqueData(UniqueDataType.CHIMAERA_WING_DATS));
            statement.setLong(10, dataSnapshot.getAbilityDATS(SuperAbilityType.ARCHERY_SUPER));
            statement.setLong(11, dataSnapshot.getAbilityDATS(SuperAbilityType.TRIDENT_SUPER));
            statement.setLong(12, dataSnapshot.getAbilityDATS(SuperAbilityType.SUPER_SHOTGUN));
            statement.setInt(13, id);
            success = (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                mcMMO.p.getLogger().severe("Failed to update cooldowns for " + dataSnapshot.getPlayerName());
                return false;
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "huds SET mobhealthbar = ?, scoreboardtips = ? WHERE user_id = ?");
            statement.setString(1, Config.getInstance().getMobHealthbarDefault().name());
            statement.setInt(2, dataSnapshot.getScoreboardTipsShown());
            statement.setInt(3, id);
            success = (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                mcMMO.p.getLogger().severe("Failed to update hud settings for " + dataSnapshot.getPlayerName());
                return false;
            }

            //XP BAR STUFF

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "xpbar SET "
                    + " view_taming = ?, view_mining = ?, view_repair = ?, view_woodcutting = ?"
                    + ", view_unarmed = ?, view_herbalism = ?, view_excavation = ?"
                    + ", view_archery = ?, view_swords = ?, view_axes = ?, view_acrobatics = ?"
                    + ", view_fishing = ?, view_alchemy = ?, view_salvage = ?, view_smelting = ?, view_tridents = ?, view_crossbows = ? WHERE user_id = ?");
            statement.setString(1, dataSnapshot.getBarStateMap().get(CoreSkills.TAMING_CS).toString());
            statement.setString(2, dataSnapshot.getBarStateMap().get(CoreSkills.MINING_CS).toString());
            statement.setString(3, dataSnapshot.getBarStateMap().get(CoreSkills.REPAIR_CS).toString());
            statement.setString(4, dataSnapshot.getBarStateMap().get(CoreSkills.WOODCUTTING_CS).toString());
            statement.setString(5, dataSnapshot.getBarStateMap().get(CoreSkills.UNARMED_CS).toString());
            statement.setString(6, dataSnapshot.getBarStateMap().get(CoreSkills.HERBALISM_CS).toString());
            statement.setString(7, dataSnapshot.getBarStateMap().get(CoreSkills.EXCAVATION_CS).toString());
            statement.setString(8, dataSnapshot.getBarStateMap().get(CoreSkills.ARCHERY_CS).toString());
            statement.setString(9, dataSnapshot.getBarStateMap().get(CoreSkills.SWORDS_CS).toString());
            statement.setString(10, dataSnapshot.getBarStateMap().get(CoreSkills.AXES_CS).toString());
            statement.setString(11, dataSnapshot.getBarStateMap().get(CoreSkills.ACROBATICS_CS).toString());
            statement.setString(12, dataSnapshot.getBarStateMap().get(CoreSkills.FISHING_CS).toString());
            statement.setString(13, dataSnapshot.getBarStateMap().get(CoreSkills.ALCHEMY_CS).toString());
            statement.setString(14, dataSnapshot.getBarStateMap().get(CoreSkills.SALVAGE_CS).toString());
            statement.setString(15, dataSnapshot.getBarStateMap().get(CoreSkills.SMELTING_CS).toString());
            statement.setString(16, dataSnapshot.getBarStateMap().get(CoreSkills.TRIDENTS_CS).toString());
            statement.setString(17, dataSnapshot.getBarStateMap().get(CoreSkills.CROSSBOWS_CS).toString());
            statement.setInt(18, id);
            success &= (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                mcMMO.p.getLogger().severe("Failed to update XP bar views for " + dataSnapshot.getPlayerName());
                return false;
            }


            //TOGGLES

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "toggle SET "
                    + " chatspy = ?, rankless = ? WHERE user_id = ?");
            statement.setBoolean(1, dataSnapshot.getPartyChatSpying());
            statement.setBoolean(2, dataSnapshot.isLeaderBoardExcluded());
            statement.setInt(3, id);
            success &= (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                mcMMO.p.getLogger().severe("Failed to update user toggles for " + dataSnapshot.getPlayerName());
                return false;
            }

        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }

        return success;
    }

    public @NotNull List<PlayerStat> readLeaderboard(@Nullable RootSkill rootSkill, int pageNumber, int statsPerPage) throws InvalidSkillException {
        List<PlayerStat> stats = new ArrayList<>();

        //Fix for a plugin that people are using that is throwing SQL errors
        if(rootSkill != null && CoreSkills.isChildSkill(rootSkill)) {
            mcMMO.p.getLogger().severe("A plugin hooking into mcMMO is being naughty with our database commands, update all plugins that hook into mcMMO and contact their devs!");
            throw new InvalidSkillException("A plugin hooking into mcMMO that you are using is attempting to read leaderboard skills for child skills, child skills do not have leaderboards! This is NOT an mcMMO error!");
        }


        String query = rootSkill == null ? ALL_QUERY_VERSION : rootSkill.getSkillName().toLowerCase(Locale.ENGLISH);
        ResultSet resultSet = null;
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("SELECT " + query + ", user FROM " + tablePrefix + "users JOIN " + tablePrefix
                    + "skills ON (user_id = id) WHERE " + query + " > 0 AND NOT user = '\\_INVALID\\_OLD\\_USERNAME\\_' ORDER BY "
                    + query + " DESC, user LIMIT ?, ?");
            statement.setInt(1, (pageNumber * statsPerPage) - statsPerPage);
            statement.setInt(2, statsPerPage);
            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                ArrayList<String> column = new ArrayList<>();

                for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                    column.add(resultSet.getString(i));
                }

                stats.add(new PlayerStat(column.get(1), Integer.parseInt(column.get(0))));
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return stats;
    }

    public @NotNull Map<RootSkill, Integer> readRank(@NotNull String playerName) {
        Map<RootSkill, Integer> skills = new HashMap<>();

        ResultSet resultSet = null;
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            for (RootSkill rootSkill : CoreSkills.getNonChildSkills()) {
                String skillName = rootSkill.getSkillName().toLowerCase(Locale.ENGLISH);
                // Get count of all users with higher skill level than player
                String sql = "SELECT COUNT(*) AS 'rank' FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE " + skillName + " > 0 " +
                        "AND " + skillName + " > (SELECT " + skillName + " FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                        "WHERE user = ?)";

                statement = connection.prepareStatement(sql);
                statement.setString(1, playerName);
                resultSet = statement.executeQuery();

                resultSet.next();

                int rank = resultSet.getInt("rank");

                // Ties are settled by alphabetical order
                sql = "SELECT user, " + skillName + " FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE " + skillName + " > 0 " +
                        "AND " + skillName + " = (SELECT " + skillName + " FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                        "WHERE user = '" + playerName + "') ORDER BY user";

                resultSet.close();
                statement.close();

                statement = connection.prepareStatement(sql);
                resultSet = statement.executeQuery();

                while (resultSet.next()) {
                    if (resultSet.getString("user").equalsIgnoreCase(playerName)) {
                        skills.put(rootSkill, rank + resultSet.getRow());
                        break;
                    }
                }

                resultSet.close();
                statement.close();
            }

            String sql = "SELECT COUNT(*) AS 'rank' FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                    "WHERE " + ALL_QUERY_VERSION + " > 0 " +
                    "AND " + ALL_QUERY_VERSION + " > " +
                    "(SELECT " + ALL_QUERY_VERSION + " " +
                    "FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE user = ?)";

            statement = connection.prepareStatement(sql);
            statement.setString(1, playerName);
            resultSet = statement.executeQuery();

            resultSet.next();

            int rank = resultSet.getInt("rank");

            resultSet.close();
            statement.close();

            sql = "SELECT user, " + ALL_QUERY_VERSION + " " +
                    "FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                    "WHERE " + ALL_QUERY_VERSION + " > 0 " +
                    "AND " + ALL_QUERY_VERSION + " = " +
                    "(SELECT " + ALL_QUERY_VERSION + " " +
                    "FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE user = ?) ORDER BY user";

            statement = connection.prepareStatement(sql);
            statement.setString(1, playerName);
            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                if (resultSet.getString("user").equalsIgnoreCase(playerName)) {
                    skills.put(null, rank + resultSet.getRow());
                    break;
                }
            }

            resultSet.close();
            statement.close();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return skills;
    }

    public void insertNewUser(@NotNull String playerName, @NotNull UUID uuid) {
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            newUser(connection, playerName, uuid);
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(connection);
        }
    }

    private int newUser(Connection connection, String playerName, UUID uuid) {
        ResultSet resultSet = null;
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement(
                    "UPDATE `" + tablePrefix + "users` "
                            + "SET user = ? "
                            + "WHERE user = ?");
            statement.setString(1, "_INVALID_OLD_USERNAME_");
            statement.setString(2, playerName);
            statement.executeUpdate();
            statement.close();
            statement = connection.prepareStatement("INSERT INTO " + tablePrefix + "users (user, uuid, lastlogin) VALUES (?, ?, UNIX_TIMESTAMP())", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, playerName);
            statement.setString(2, uuid != null ? uuid.toString() : null);
            statement.executeUpdate();

            resultSet = statement.getGeneratedKeys();

            if (!resultSet.next()) {
                mcMMO.p.getLogger().severe("Unable to create new user account in DB");
                return -1;
            }

            writeMissingRows(connection, resultSet.getInt(1));
            return resultSet.getInt(1);
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
        }
        return -1;
    }

    @Override
    public @Nullable MMOPlayerData queryPlayerDataByPlayer(@NotNull Player player) throws ProfileRetrievalException, NullArgumentException {
        return loadPlayerProfile(player, player.getName(), player.getUniqueId());
    }

    @Override
    public @Nullable MMOPlayerData queryPlayerDataByUUID(@NotNull UUID uuid, @NotNull String playerName) throws ProfileRetrievalException, NullArgumentException {
        return loadPlayerProfile(null, playerName, uuid);
    }

    private @Nullable MMOPlayerData loadPlayerProfile(@Nullable Player player, @NotNull String playerName, @Nullable UUID playerUUID) {
        PreparedStatement statement = null;
        Connection connection = null;
        ResultSet resultSet = null;

        try {
            connection = getConnection(PoolIdentifier.LOAD);
            int id = getUserID(connection, playerName, playerUUID);

            if (id == -1) {
                // There is no such user
                if (player != null) {
                    id = newUser(connection, playerName, playerUUID);
                    if (id == -1) {
                        return null;
                    }
                } else {
                    return null;
                }
            }
            // There is such a user
            writeMissingRows(connection, id);

            statement = getUserData(connection);
            statement.setInt(1, id);

            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                try {
                    MMOPlayerData mmoPlayerData = loadFromResult(playerName, resultSet);
                    String name = resultSet.getString(42); // TODO: Magic Number, make sure it stays updated
                    resultSet.close();
                    statement.close();

                    if (!playerName.isEmpty() && !playerName.equalsIgnoreCase(name) && playerUUID != null) {
                        statement = connection.prepareStatement(
                                "UPDATE `" + tablePrefix + "users` "
                                        + "SET user = ? "
                                        + "WHERE user = ?");
                        statement.setString(1, "_INVALID_OLD_USERNAME_");
                        statement.setString(2, name);
                        statement.executeUpdate();
                        statement.close();
                        statement = connection.prepareStatement(
                                "UPDATE `" + tablePrefix + "users` "
                                        + "SET user = ?, uuid = ? "
                                        + "WHERE id = ?");
                        statement.setString(1, playerName);
                        statement.setString(2, playerUUID.toString());
                        statement.setInt(3, id);
                        statement.executeUpdate();
                        statement.close();
                    }

                    return mmoPlayerData;
                }
                catch (SQLException e) {
                    printErrors(e);
                }
            }
            resultSet.close();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return null;
    }

    public void convertUsers(@NotNull DatabaseManager destination) {
        PreparedStatement statement = null;
        Connection connection = null;
        ResultSet resultSet = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = getUserData(connection);
            List<String> usernames = getStoredUsers();
            int convertedUsers = 0;
            long startMillis = System.currentTimeMillis();
            for (String playerName : usernames) {
                statement.setString(1, playerName);
                try {
                    resultSet = statement.executeQuery();
                    resultSet.next();
                    //TODO: Optimize, probably needless to make a snapshot here, brain tired
                    MMOPlayerData mmoPlayerData = loadFromResult(playerName, resultSet);
                    MMODataSnapshot mmoDataSnapshot = mcMMO.getUserManager().createPlayerDataSnapshot(mmoPlayerData);
                    destination.saveUser(mmoDataSnapshot);
                    resultSet.close();
                }
                catch (SQLException e) {
                    printErrors(e);
                    // Ignore
                }
                convertedUsers++;
                Misc.printProgress(convertedUsers, progressInterval, startMillis);
            }
        }
        catch (SQLException e) {
            printErrors(e);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

    }

    private @NotNull PreparedStatement getUserData(@NotNull Connection connection) throws SQLException {
        return connection.prepareStatement(
                "SELECT "
                        + "s.taming, s.mining, s.repair, s.woodcutting, s.unarmed, s.herbalism, s.excavation, s.archery, s.swords, s.axes, s.acrobatics, s.fishing, s.alchemy, s.tridents, s.crossbows, "
                        + "e.taming, e.mining, e.repair, e.woodcutting, e.unarmed, e.herbalism, e.excavation, e.archery, e.swords, e.axes, e.acrobatics, e.fishing, e.alchemy, e.tridents, e.crossbows, "
                        + "c.taming, c.mining, c.repair, c.woodcutting, c.unarmed, c.herbalism, c.excavation, c.archery, c.swords, c.axes, c.acrobatics, c.blast_mining, c.chimaera_wing, c.tridents, c.crossbows, "
                        + "h.mobhealthbar, h.scoreboardtips, u.uuid "
                        + "x.view_taming, x.view_mining, x.view_repair, x.view_woodcutting, x.view_unarmed, x.view_herbalism, x.view_excavation, x.view_archery, x.view_swords, x.view_axes, x.view_acrobatics, x.view_salvage, x.view_smelting, x.view_tridents, x.view_crossbows, "
                        + "t.chatspy, t.rankless, "
                        + "FROM " + tablePrefix + "users u "
                        + "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) "
                        + "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) "
                        + "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) "
                        + "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) "
                        + "JOIN " + tablePrefix + "xpbar x ON (u.id = x.user_id) "
                        + "JOIN " + tablePrefix + "toggle t ON (u.id = t.user_id) "
                        + "WHERE u.user = ?");
    }


    public boolean saveUserUUID(@NotNull String userName, @NotNull UUID uuid) {
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement(
                    "UPDATE `" + tablePrefix + "users` SET "
                            + "  uuid = ? WHERE user = ?");
            statement.setString(1, uuid.toString());
            statement.setString(2, userName);
            statement.execute();
            return true;
        }
        catch (SQLException ex) {
            printErrors(ex);
            return false;
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }
    }

//    public boolean saveUserUUIDs(Map<String, UUID> fetchedUUIDs) {
//        PreparedStatement statement = null;
//        int count = 0;
//
//        Connection connection = null;
//
//        try {
//            connection = getConnection(PoolIdentifier.MISC);
//            statement = connection.prepareStatement("UPDATE " + tablePrefix + "users SET uuid = ? WHERE user = ?");
//
//            for (Map.Entry<String, UUID> entry : fetchedUUIDs.entrySet()) {
//                statement.setString(1, entry.getValue().toString());
//                statement.setString(2, entry.getKey());
//
//                statement.addBatch();
//
//                count++;
//
//                if ((count % 500) == 0) {
//                    statement.executeBatch();
//                    count = 0;
//                }
//            }
//
//            if (count != 0) {
//                statement.executeBatch();
//            }
//
//            return true;
//        }
//        catch (SQLException ex) {
//            printErrors(ex);
//            return false;
//        }
//        finally {
//            tryClose(statement);
//            tryClose(connection);
//        }
//    }

    public @NotNull List<String> getStoredUsers() {
        ArrayList<String> users = new ArrayList<>();

        Statement statement = null;
        Connection connection = null;
        ResultSet resultSet = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.createStatement();
            resultSet = statement.executeQuery("SELECT user FROM " + tablePrefix + "users");
            while (resultSet.next()) {
                users.add(resultSet.getString("user"));
            }
        }
        catch (SQLException e) {
            printErrors(e);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return users;
    }

    /**
     * Checks that the database structure is present and correct
     */
    private void checkStructure() {

        PreparedStatement statement = null;
        Statement createStatement = null;
        ResultSet resultSet = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("SELECT table_name FROM INFORMATION_SCHEMA.TABLES"
                    + " WHERE table_schema = ?"
                    + " AND table_name = ?");
            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "users");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "users` ("
                    + "`id` int(10) unsigned NOT NULL AUTO_INCREMENT,"
                    + "`user` varchar(40) NOT NULL,"
                    + "`uuid` varchar(36) NULL DEFAULT NULL,"
                    + "`lastlogin` int(32) unsigned NOT NULL,"
                    + "PRIMARY KEY (`id`),"
                    + "INDEX(`user`(20) ASC),"
                    + "UNIQUE KEY `uuid` (`uuid`)) DEFAULT CHARSET=latin1 AUTO_INCREMENT=1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "huds");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "huds` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`mobhealthbar` varchar(50) NOT NULL DEFAULT '" + Config.getInstance().getMobHealthbarDefault() + "',"
                        + "`scoreboardtips` int(10) NOT NULL DEFAULT '0',"
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "cooldowns");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "cooldowns` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`taming` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`mining` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`woodcutting` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`repair` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`unarmed` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`herbalism` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`excavation` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`archery` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`swords` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`axes` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`acrobatics` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`blast_mining` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`chimaera_wing` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`tridents` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`crossbows` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "skills");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "skills` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`taming` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`mining` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`woodcutting` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`repair` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`unarmed` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`herbalism` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`excavation` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`archery` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`swords` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`axes` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`acrobatics` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`fishing` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`alchemy` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`tridents` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`crossbows` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`total` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "experience");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "experience` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`taming` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`mining` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`woodcutting` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`repair` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`unarmed` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`herbalism` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`excavation` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`archery` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`swords` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`axes` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`acrobatics` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`fishing` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`alchemy` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`tridents` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`crossbows` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            tryClose(statement);

            //Toggle Table
            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "toggle");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "toggle` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`chatspy` bit NOT NULL DEFAULT '0',"
                        + "`rankless` bit NOT NULL DEFAULT '0',"
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            tryClose(statement);

            //XP Bar Table
            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "xpbar");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "xpbar` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`view_taming` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "`view_mining` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "`view_woodcutting` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "`view_repair` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "`view_unarmed` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "`view_herbalism` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "`view_excavation` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "`view_archery` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "`view_swords` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "`view_axes` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "`view_acrobatics` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "`view_salvage` varchar(40) NOT NULL DEFAULT 'DISABLED',"
                        + "`view_smelting` varchar(40) NOT NULL DEFAULT 'DISABLED',"
                        + "`view_tridents` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "`view_crossbows` varchar(40) NOT NULL DEFAULT 'NORMAL',"
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            tryClose(statement);

            for (UpgradeType updateType : UpgradeType.values()) {
                checkDatabaseStructure(connection, updateType);
            }

            if (Config.getInstance().getTruncateSkills()) {
                for (RootSkill rootSkill : CoreSkills.getNonChildSkills()) {
                    int cap = Config.getInstance().getLevelCap(rootSkill);
                    if (cap != Integer.MAX_VALUE) {
                        statement = connection.prepareStatement("UPDATE `" + tablePrefix + "skills` SET `"
                                + rootSkill.getSkillName().toLowerCase(Locale.ENGLISH) + "` = " + cap + " WHERE `"
                                + rootSkill.getSkillName().toLowerCase(Locale.ENGLISH) + "` > " + cap);
                        statement.executeUpdate();
                        tryClose(statement);
                    }
                }
            }

            mcMMO.p.getLogger().info("Killing orphans");
            createStatement = connection.createStatement();
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "experience` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "experience`.`user_id` = `u`.`id`)");
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "huds` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "huds`.`user_id` = `u`.`id`)");
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "cooldowns` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "cooldowns`.`user_id` = `u`.`id`)");
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "skills` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "skills`.`user_id` = `u`.`id`)");
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "toggle` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "skills`.`user_id` = `u`.`id`)");
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "xpbar` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "skills`.`user_id` = `u`.`id`)");
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(createStatement);
            tryClose(connection);
        }

    }

    private Connection getConnection(PoolIdentifier identifier) throws SQLException {
        Connection connection = null;
        switch (identifier) {
            case LOAD:
                connection = loadPool.getConnection();
                break;
            case MISC:
                connection = miscPool.getConnection();
                break;
            case SAVE:
                connection = savePool.getConnection();
                break;
        }
        if (connection == null) {
            throw new RuntimeException("getConnection() for " + identifier.name().toLowerCase(Locale.ENGLISH) + " pool timed out.  Increase max connections settings.");
        }
        return connection;
    }

    /**
     * Check database structure for necessary upgrades.
     *
     * @param upgrade Upgrade to attempt to apply
     */
    private void checkDatabaseStructure(Connection connection, UpgradeType upgrade) {
        if (!mcMMO.getUpgradeManager().shouldUpgrade(upgrade)) {
            mcMMO.p.getLogger().info("Skipping " + upgrade.name() + " upgrade (unneeded)");
            return;
        }

        Statement statement = null;

        try {
            statement = connection.createStatement();

            switch (upgrade) {
                case ADD_FISHING:
                    checkUpgradeAddFishing(statement);
                    break;

                case ADD_BLAST_MINING_COOLDOWN:
                    checkUpgradeAddBlastMiningCooldown(statement);
                    break;

                case ADD_SQL_INDEXES:
                    checkUpgradeAddSQLIndexes(statement);
                    break;

                case ADD_MOB_HEALTHBARS:
                    checkUpgradeAddMobHealthbars(statement);
                    break;

                case DROP_SQL_PARTY_NAMES:
                    checkUpgradeDropPartyNames(statement);
                    break;

                case DROP_SPOUT:
                    checkUpgradeDropSpout(statement);
                    break;

                case ADD_ALCHEMY:
                    checkUpgradeAddAlchemy(statement);
                    break;

                case ADD_UUIDS:
                    checkUpgradeAddUUIDs(statement);
                    return;
                case ADD_SCOREBOARD_TIPS:
                    checkUpgradeAddScoreboardTips(statement);
                    return;

                case DROP_NAME_UNIQUENESS:
                    checkNameUniqueness(statement);
                    return;

                case ADD_SKILL_TOTAL:
                    checkUpgradeSkillTotal(connection);
                    break;
                case ADD_UNIQUE_PLAYER_DATA:
                    checkUpgradeAddUniqueChimaeraWing(statement);
                    break;
                case ADD_SQL_2_2:
                    checkUpgradeAddTridentsAndCrossbowsSQL(statement);
                    break;
                default:
                    break;

            }

            mcMMO.getUpgradeManager().setUpgradeCompleted(upgrade);
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
        }
    }

    private void writeMissingRows(Connection connection, int id) {
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "experience (user_id) VALUES (?)");
            statement.setInt(1, id);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "skills (user_id) VALUES (?)");
            statement.setInt(1, id);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "cooldowns (user_id) VALUES (?)");
            statement.setInt(1, id);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "huds (user_id, mobhealthbar, scoreboardtips) VALUES (?, ?, ?)");
            statement.setInt(1, id);
            statement.setString(2, Config.getInstance().getMobHealthbarDefault().name());
            statement.setInt(3, 0);
            statement.execute();
            statement.close();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
        }
    }

    private @Nullable MMOPlayerData loadFromResult(@NotNull String playerName, @NotNull ResultSet result) throws SQLException {
        MMODataBuilder MMODataBuilder = new MMODataBuilder();
        Map<RootSkill, Integer> skills = new HashMap<>(); // Skill & Level
        Map<RootSkill, Float> skillsXp = new HashMap<>(); // Skill & XP
        Map<SuperSkill, Integer> skillsDATS = new HashMap<>(); // Ability & Cooldown
        Map<UniqueDataType, Integer> uniqueData = new EnumMap<UniqueDataType, Integer>(UniqueDataType.class); //Chimaera wing cooldown and other misc info
        Map<RootSkill, SkillBossBarState> xpBarStateMap = new HashMap<RootSkill, SkillBossBarState>();

        MobHealthBarType mobHealthbarType;
        UUID uuid;
        int scoreboardTipsShown;

        final int parentSkills = 15, allSkills = 17, cooldownCount = 17, toggleCount = 2, otherCount = 4;
        final int OFFSET_SKILLS = 0; // TODO update these numbers when the query
        // changes (a new skill is added)
        final int OFFSET_EXPERIENCE = OFFSET_SKILLS + parentSkills;
        final int OFFSET_COOLDOWNS = OFFSET_EXPERIENCE + parentSkills;
        final int OFFSET_OTHER = OFFSET_COOLDOWNS + cooldownCount;
        final int OFFSET_XPBAR = OFFSET_OTHER + otherCount;
        final int OFFSET_TOGGLE = OFFSET_XPBAR + allSkills;

        skills.put(CoreSkills.TAMING_CS, result.getInt(OFFSET_SKILLS + 1));
        skills.put(CoreSkills.MINING_CS, result.getInt(OFFSET_SKILLS + 2));
        skills.put(CoreSkills.REPAIR_CS, result.getInt(OFFSET_SKILLS + 3));
        skills.put(CoreSkills.WOODCUTTING_CS, result.getInt(OFFSET_SKILLS + 4));
        skills.put(CoreSkills.UNARMED_CS, result.getInt(OFFSET_SKILLS + 5));
        skills.put(CoreSkills.HERBALISM_CS, result.getInt(OFFSET_SKILLS + 6));
        skills.put(CoreSkills.EXCAVATION_CS, result.getInt(OFFSET_SKILLS + 7));
        skills.put(CoreSkills.ARCHERY_CS, result.getInt(OFFSET_SKILLS + 8));
        skills.put(CoreSkills.SWORDS_CS, result.getInt(OFFSET_SKILLS + 9));
        skills.put(CoreSkills.AXES_CS, result.getInt(OFFSET_SKILLS + 10));
        skills.put(CoreSkills.ACROBATICS_CS, result.getInt(OFFSET_SKILLS + 11));
        skills.put(CoreSkills.FISHING_CS, result.getInt(OFFSET_SKILLS + 12));
        skills.put(CoreSkills.ALCHEMY_CS, result.getInt(OFFSET_SKILLS + 13));
        skills.put(CoreSkills.TRIDENTS_CS, result.getInt(OFFSET_SKILLS + 14));
        skills.put(CoreSkills.CROSSBOWS_CS, result.getInt(OFFSET_SKILLS + 15));

        skillsXp.put(CoreSkills.TAMING_CS, result.getFloat(OFFSET_EXPERIENCE + 1));
        skillsXp.put(CoreSkills.MINING_CS, result.getFloat(OFFSET_EXPERIENCE + 2));
        skillsXp.put(CoreSkills.REPAIR_CS, result.getFloat(OFFSET_EXPERIENCE + 3));
        skillsXp.put(CoreSkills.WOODCUTTING_CS, result.getFloat(OFFSET_EXPERIENCE + 4));
        skillsXp.put(CoreSkills.UNARMED_CS, result.getFloat(OFFSET_EXPERIENCE + 5));
        skillsXp.put(CoreSkills.HERBALISM_CS, result.getFloat(OFFSET_EXPERIENCE + 6));
        skillsXp.put(CoreSkills.EXCAVATION_CS, result.getFloat(OFFSET_EXPERIENCE + 7));
        skillsXp.put(CoreSkills.ARCHERY_CS, result.getFloat(OFFSET_EXPERIENCE + 8));
        skillsXp.put(CoreSkills.SWORDS_CS, result.getFloat(OFFSET_EXPERIENCE + 9));
        skillsXp.put(CoreSkills.AXES_CS, result.getFloat(OFFSET_EXPERIENCE + 10));
        skillsXp.put(CoreSkills.ACROBATICS_CS, result.getFloat(OFFSET_EXPERIENCE + 11));
        skillsXp.put(CoreSkills.FISHING_CS, result.getFloat(OFFSET_EXPERIENCE + 12));
        skillsXp.put(CoreSkills.ALCHEMY_CS, result.getFloat(OFFSET_EXPERIENCE + 13));
        skillsXp.put(CoreSkills.TRIDENTS_CS, result.getFloat(OFFSET_EXPERIENCE + 14));
        skillsXp.put(CoreSkills.CROSSBOWS_CS, result.getFloat(OFFSET_EXPERIENCE + 15));

        // Taming - Unused - result.getInt(OFFSET_COOLDOWNS + 1)
        skillsDATS.put(SuperAbilityType.SUPER_BREAKER, result.getInt(OFFSET_COOLDOWNS + 2));
        // Repair - Unused - result.getInt(OFFSET_COOLDOWNS + 3)
        skillsDATS.put(SuperAbilityType.TREE_FELLER, result.getInt(OFFSET_COOLDOWNS + 4));
        skillsDATS.put(SuperAbilityType.BERSERK, result.getInt(OFFSET_COOLDOWNS + 5));
        skillsDATS.put(SuperAbilityType.GREEN_TERRA, result.getInt(OFFSET_COOLDOWNS + 6));
        skillsDATS.put(SuperAbilityType.GIGA_DRILL_BREAKER, result.getInt(OFFSET_COOLDOWNS + 7));
        skillsDATS.put(SuperAbilityType.ARCHERY_SUPER, result.getInt(OFFSET_COOLDOWNS + 8));
        skillsDATS.put(SuperAbilityType.SERRATED_STRIKES, result.getInt(OFFSET_COOLDOWNS + 9));
        skillsDATS.put(SuperAbilityType.SKULL_SPLITTER, result.getInt(OFFSET_COOLDOWNS + 10));
        // Acrobatics - Unused - result.getInt(OFFSET_COOLDOWNS + 11)
        skillsDATS.put(SuperAbilityType.BLAST_MINING, result.getInt(OFFSET_COOLDOWNS + 12));
        uniqueData.put(UniqueDataType.CHIMAERA_WING_DATS, result.getInt(OFFSET_COOLDOWNS + 13));
        skillsDATS.put(SuperAbilityType.TRIDENT_SUPER, result.getInt(OFFSET_COOLDOWNS + 14));
        skillsDATS.put(SuperAbilityType.SUPER_SHOTGUN, result.getInt(OFFSET_COOLDOWNS + 15));

        //


        try {
            mobHealthbarType = MobHealthBarType.valueOf(result.getString(OFFSET_OTHER + 1));
        }
        catch (Exception e) {
            mobHealthbarType = Config.getInstance().getMobHealthbarDefault();
        }

        try {
            scoreboardTipsShown = result.getInt(OFFSET_OTHER + 2);
        }
        catch (Exception e) {
            scoreboardTipsShown = 0;
        }

        try {
            uuid = UUID.fromString(result.getString(OFFSET_OTHER + 3));
        }
        catch (Exception e) {
            return null;
        }

        //XPBAR
        xpBarStateMap.put(CoreSkills.TAMING_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 1)));
        xpBarStateMap.put(CoreSkills.MINING_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 2)));
        xpBarStateMap.put(CoreSkills.REPAIR_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 3)));
        xpBarStateMap.put(CoreSkills.WOODCUTTING_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 4)));
        xpBarStateMap.put(CoreSkills.UNARMED_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 5)));
        xpBarStateMap.put(CoreSkills.HERBALISM_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 6)));
        xpBarStateMap.put(CoreSkills.EXCAVATION_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 7)));
        xpBarStateMap.put(CoreSkills.ARCHERY_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 8)));
        xpBarStateMap.put(CoreSkills.SWORDS_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 9)));
        xpBarStateMap.put(CoreSkills.AXES_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 10)));
        xpBarStateMap.put(CoreSkills.ACROBATICS_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 11)));
        xpBarStateMap.put(CoreSkills.FISHING_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 12)));
        xpBarStateMap.put(CoreSkills.ALCHEMY_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 13)));
        xpBarStateMap.put(CoreSkills.SALVAGE_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 14)));
        xpBarStateMap.put(CoreSkills.SMELTING_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 15)));
        xpBarStateMap.put(CoreSkills.TRIDENTS_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 16)));
        xpBarStateMap.put(CoreSkills.CROSSBOWS_CS, SkillUtils.asBarState(result.getString(OFFSET_XPBAR + 17)));

        //TOGGLE
        boolean chatSpy = result.getBoolean(OFFSET_TOGGLE+1);
        boolean rankLess = result.getBoolean(OFFSET_TOGGLE+2);

        //Build
        MMODataBuilder mmoDataBuilder = new MMODataBuilder();
        mmoDataBuilder.setSkillLevelValues(skills)
                .setSkillExperienceValues(skillsXp)
                .setAbilityDeactivationTimestamps(skillsDATS)
                .setUniquePlayerData(uniqueData)
                .setBarStateMap(xpBarStateMap)
                .setPlayerUUID(uuid)
                .setPlayerName(playerName)
                .setPartyChatSpying(chatSpy)
                .setLastLogin(0) //TODO: Program this in properly
                .setScoreboardTipsShown(scoreboardTipsShown)
                .setLeaderBoardExemption(rankLess);


        try {
            return mmoDataBuilder.build();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

    }

    private void printErrors(SQLException ex) {
        if (debug) {
            ex.printStackTrace();
        }

        StackTraceElement element = ex.getStackTrace()[0];
        mcMMO.p.getLogger().severe("Location: " + element.getClassName() + " " + element.getMethodName() + " " + element.getLineNumber());
        mcMMO.p.getLogger().severe("SQLException: " + ex.getMessage());
        mcMMO.p.getLogger().severe("SQLState: " + ex.getSQLState());
        mcMMO.p.getLogger().severe("VendorError: " + ex.getErrorCode());
    }

    public @NotNull DatabaseType getDatabaseType() {
        return DatabaseType.SQL;
    }

    private void checkNameUniqueness(final Statement statement) {
        ResultSet resultSet = null;
        try {
            resultSet = statement.executeQuery("SHOW INDEXES "
                    + "FROM `" + tablePrefix + "users` "
                    + "WHERE Column_name='user' "
                    + " AND NOT Non_unique");
            if (!resultSet.next()) {
                return;
            }
            resultSet.close();
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables to drop name uniqueness...");
            statement.execute("ALTER TABLE `" + tablePrefix + "users` " 
                    + "DROP INDEX `user`,"
                    + "ADD INDEX `user` (`user`(20) ASC)");
        } catch (SQLException ex) {
            ex.printStackTrace();
        } finally {
            tryClose(resultSet);
        }
    }

    private void checkUpgradeAddTridentsAndCrossbowsSQL(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT 'tridents' FROM `" + tablePrefix +"cooldowns` LIMIT 1");
        } catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating SQL DB tables for 2.2 Update....");

            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "cooldowns` ADD `tridents` int(10) NOT NULL DEFAULT '0'");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "cooldowns` ADD `crossbows` int(10) NOT NULL DEFAULT '0'");

            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD `tridents` int(10) NOT NULL DEFAULT '0'");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD `crossbows` int(10) NOT NULL DEFAULT '0'");

            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "experience` ADD `tridents` int(10) NOT NULL DEFAULT '0'");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "experience` ADD `crossbows` int(10) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddAlchemy(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `alchemy` FROM `" + tablePrefix + "skills` LIMIT 1");
        }
        catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for Alchemy...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD `alchemy` int(10) NOT NULL DEFAULT '0'");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "experience` ADD `alchemy` int(10) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddBlastMiningCooldown(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `blast_mining` FROM `" + tablePrefix + "cooldowns` LIMIT 1");
        }
        catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for Blast Mining...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "cooldowns` ADD `blast_mining` int(32) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddUniqueChimaeraWing(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `chimaera_wing` FROM `" + tablePrefix + "cooldowns` LIMIT 1");
        }
        catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for Chimaera Wing...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "cooldowns` ADD `chimaera_wing` int(32) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddFishing(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `fishing` FROM `" + tablePrefix + "skills` LIMIT 1");
        }
        catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for Fishing...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD `fishing` int(10) NOT NULL DEFAULT '0'");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "experience` ADD `fishing` int(10) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddMobHealthbars(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `mobhealthbar` FROM `" + tablePrefix + "huds` LIMIT 1");
        }
        catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for mob healthbars...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "huds` ADD `mobhealthbar` varchar(50) NOT NULL DEFAULT '" + Config.getInstance().getMobHealthbarDefault() + "'");
        }
    }

    private void checkUpgradeAddScoreboardTips(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `scoreboardtips` FROM `" + tablePrefix + "huds` LIMIT 1");
        }
        catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for scoreboard tips...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "huds` ADD `scoreboardtips` int(10) NOT NULL DEFAULT '0' ;");
        }
    }

    private void checkUpgradeAddSQLIndexes(final Statement statement) {
        ResultSet resultSet = null;

        try {
            resultSet = statement.executeQuery("SHOW INDEX FROM `" + tablePrefix + "skills` WHERE `Key_name` LIKE 'idx\\_%'");
            resultSet.last();

            if (resultSet.getRow() != CoreSkills.getNonChildSkills().size()) {
                mcMMO.p.getLogger().info("Indexing tables, this may take a while on larger databases");

                for (RootSkill rootSkill : CoreSkills.getNonChildSkills()) {
                    String skill_name = rootSkill.getSkillName().toLowerCase(Locale.ENGLISH);

                    try {
                        statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD INDEX `idx_" + skill_name + "` (`" + skill_name + "`) USING BTREE");
                    }
                    catch (SQLException ex) {
                        // Ignore
                    }
                }
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
        }
    }

    private void checkUpgradeAddUUIDs(final Statement statement) {
        ResultSet resultSet = null;

        try {
            resultSet = statement.executeQuery("SELECT * FROM `" + tablePrefix + "users` LIMIT 1");

            ResultSetMetaData rsmeta = resultSet.getMetaData();
            boolean column_exists = false;

            for (int i = 1; i <= rsmeta.getColumnCount(); i++) {
                if (rsmeta.getColumnName(i).equalsIgnoreCase("uuid")) {
                    column_exists = true;
                    break;
                }
            }

            if (!column_exists) {
                mcMMO.p.getLogger().info("Adding UUIDs to mcMMO MySQL user table...");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "users` ADD `uuid` varchar(36) NULL DEFAULT NULL");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "users` ADD UNIQUE INDEX `uuid` (`uuid`) USING BTREE");
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
        }

        new GetUUIDUpdatesRequired().runTaskLaterAsynchronously(mcMMO.p, 100); // wait until after first purge
    }

    private class GetUUIDUpdatesRequired extends BukkitRunnable {
        public void run() {
            massUpdateLock.lock();
            List<String> names = new ArrayList<>();
            Connection connection = null;
            Statement statement = null;
            ResultSet resultSet = null;
            try {
                try {
                    connection = miscPool.getConnection();
                    statement = connection.createStatement();
                    resultSet = statement.executeQuery("SELECT `user` FROM `" + tablePrefix + "users` WHERE `uuid` IS NULL");

                    while (resultSet.next()) {
                        names.add(resultSet.getString("user"));
                    }
                } catch (SQLException ex) {
                    printErrors(ex);
                } finally {
                    tryClose(resultSet);
                    tryClose(statement);
                    tryClose(connection);
                }

                if (!names.isEmpty()) {
                    UUIDUpdateAsyncTask updateTask = new UUIDUpdateAsyncTask(mcMMO.p, names);
                    updateTask.start();
                    updateTask.waitUntilFinished();
                }
            } finally {
                massUpdateLock.unlock();
            }
        }
    }

    private void checkUpgradeDropPartyNames(final Statement statement) {
        ResultSet resultSet = null;

        try {
            resultSet = statement.executeQuery("SELECT * FROM `" + tablePrefix + "users` LIMIT 1");

            ResultSetMetaData rsmeta = resultSet.getMetaData();
            boolean column_exists = false;

            for (int i = 1; i <= rsmeta.getColumnCount(); i++) {
                if (rsmeta.getColumnName(i).equalsIgnoreCase("party")) {
                    column_exists = true;
                    break;
                }
            }

            if (column_exists) {
                mcMMO.p.getLogger().info("Removing party name from users table...");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "users` DROP COLUMN `party`");
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
        }
    }

    private void checkUpgradeSkillTotal(final Connection connection) throws SQLException {
        ResultSet resultSet = null;
        Statement statement = null;

        try {
            connection.setAutoCommit(false);
            statement = connection.createStatement();
            resultSet = statement.executeQuery("SELECT * FROM `" + tablePrefix + "skills` LIMIT 1");

            ResultSetMetaData rsmeta = resultSet.getMetaData();
            boolean column_exists = false;

            for (int i = 1; i <= rsmeta.getColumnCount(); i++) {
                if (rsmeta.getColumnName(i).equalsIgnoreCase("total")) {
                    column_exists = true;
                    break;
                }
            }

            if (!column_exists) {
                mcMMO.p.getLogger().info("Adding skill total column to skills table...");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD COLUMN `total` int NOT NULL DEFAULT '0'");
                statement.executeUpdate("UPDATE `" + tablePrefix + "skills` SET `total` = (taming+mining+woodcutting+repair+unarmed+herbalism+excavation+archery+swords+axes+acrobatics+fishing+alchemy)");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD INDEX `idx_total` (`total`) USING BTREE");
                connection.commit();
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            connection.setAutoCommit(true);
            tryClose(resultSet);
            tryClose(statement);
        }
    }

    private void checkUpgradeDropSpout(final Statement statement) {
        ResultSet resultSet = null;

        try {
            resultSet = statement.executeQuery("SELECT * FROM `" + tablePrefix + "huds` LIMIT 1");

            ResultSetMetaData rsmeta = resultSet.getMetaData();
            boolean column_exists = false;

            for (int i = 1; i <= rsmeta.getColumnCount(); i++) {
                if (rsmeta.getColumnName(i).equalsIgnoreCase("hudtype")) {
                    column_exists = true;
                    break;
                }
            }

            if (column_exists) {
                mcMMO.p.getLogger().info("Removing Spout HUD type from huds table...");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "huds` DROP COLUMN `hudtype`");
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
        }
    }

    private int getUserID(final Connection connection, final String playerName, final UUID uuid) {
        if (uuid == null)
            return getUserIDByName(connection, playerName);

        if (cachedUserIDs.containsKey(uuid))
            return cachedUserIDs.get(uuid);

        ResultSet resultSet = null;
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("SELECT id, user FROM " + tablePrefix + "users WHERE uuid = ? OR (uuid IS NULL AND user = ?)");
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                int id = resultSet.getInt("id");

                cachedUserIDs.put(uuid, id);

                return id;
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
        }

        return -1;
    }

    private int getUserIDByName(final Connection connection, final String playerName) {
        ResultSet resultSet = null;
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("SELECT id, user FROM " + tablePrefix + "users WHERE user = ?");
            statement.setString(1, playerName);
            resultSet = statement.executeQuery();

            if (resultSet.next()) {

                return resultSet.getInt("id");
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
        }

        return -1;
    }
    
    private void tryClose(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            }
            catch (Exception e) {
                // Ignore
            }
        }
    }

    @Override
    public void onDisable() {
        mcMMO.p.getLogger().info("Releasing connection pool resource...");
        miscPool.close();
        loadPool.close();
        savePool.close();
    }

    public enum PoolIdentifier {
        MISC,
        LOAD,
        SAVE
    }

    public void resetMobHealthSettings() {
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("UPDATE " + tablePrefix + "huds SET mobhealthbar = ?");
            statement.setString(1, Config.getInstance().getMobHealthbarDefault().toString());
            statement.executeUpdate();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }
    }

    @Override
    public void removeCache(@NotNull UUID uuid) {
        cachedUserIDs.remove(uuid);
    }
}
