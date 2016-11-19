package net.arcation.arcadion;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import net.arcation.arcadion.interfaces.Insertable;
import net.arcation.arcadion.interfaces.Selectable;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedTransferQueue;

/**
 * Created by Mr_Little_Kitty on 11/7/2016.
 */
public class Arcadion extends JavaPlugin implements net.arcation.arcadion.interfaces.Arcadion
{
    public static net.arcation.arcadion.interfaces.Arcadion instance;

    private List<DisableableThread> threads;
    private HikariDataSource dataSource;

    private LinkedTransferQueue<Insertable> asyncInsertables;
    private LinkedTransferQueue<Selectable> asyncSelectables;

    private static String HOST_PATH = "database.host";
    private static String PORT_PATH = "database.port";
    private static String DATABASE_PATH = "database.database";
    private static String USERNAME_PATH = "database.username";
    private static String PASSWORD_PATH = "database.password";

    private static String MAX_CONNECTIONS_PATH = "settings.maxConnections";
    private static String SELECT_THREADS = "settings.selectThreads";
    private static String INSERT_THREADS = "settings.insertThreads";

    @Override
    public void onEnable()
    {
        instance = this;

        addDefaults();

        FileConfiguration pluginConfig = getConfig();
        String hostname = pluginConfig.getString(HOST_PATH);
        int port = pluginConfig.getInt(PORT_PATH);
        String databaseName = pluginConfig.getString(DATABASE_PATH);
        String user = pluginConfig.getString(USERNAME_PATH);
        String pass = pluginConfig.getString(PASSWORD_PATH);

        int maxConnections = pluginConfig.getInt(MAX_CONNECTIONS_PATH);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + hostname + ":" + port + "/" + databaseName);
        config.setUsername(user);
        config.setPassword(pass);
        config.setMaximumPoolSize(maxConnections);

        asyncInsertables = new LinkedTransferQueue<>();
        asyncSelectables = new LinkedTransferQueue<>();

        try
        {
            dataSource = new HikariDataSource(config);
        }
        catch(HikariPool.PoolInitializationException e)
        {
            dataSource = null;
            e.printStackTrace();
        }

        if(isActive())
        {
            this.getLogger().info("[Arcadion] Successfully connected to the database server!");
            threads = new ArrayList<>();

            int insertThreads = pluginConfig.getInt(INSERT_THREADS);
            int selectThreads = pluginConfig.getInt(SELECT_THREADS);

            for(int i = 0; i < selectThreads; i++)
            {
                SelectThread t = new SelectThread(this);
                threads.add(t);
                t.start();
                this.getLogger().info("[Arcadion] Created Select Thread #" + (i + 1));
            }
            for(int i = 0; i < insertThreads; i++)
            {
                InsertThread t = new InsertThread(this);
                threads.add(t);
                t.start();
                this.getLogger().info("[Arcadion] Created Insert Thread #" + (i + 1));
            }
        }
        else
            this.getLogger().info("[Arcadion] ERROR Could not connect to the database server!");
    }

    @Override
    public void onDisable()
    {
        if(dataSource != null && !dataSource.isClosed())
            dataSource.close();
    }

    private void addDefaults()
    {
        FileConfiguration config = getConfig();

        config.addDefault(HOST_PATH,"127.0.0.1");
        config.addDefault(PORT_PATH,3380);
        config.addDefault(DATABASE_PATH,"civex");
        config.addDefault(USERNAME_PATH,"root");
        config.addDefault(PASSWORD_PATH,"pass");

        config.addDefault(MAX_CONNECTIONS_PATH, 6);
        config.addDefault(SELECT_THREADS, 1);
        config.addDefault(INSERT_THREADS, 1);

        config.options().copyDefaults(true);

        this.saveConfig();
    }

    public boolean isActive()
    {
        return dataSource != null && !dataSource.isClosed();
    }

    public void queueAsyncInsertable(Insertable insertable)
    {
        if(insertable != null)
            asyncInsertables.offer(insertable);
    }

    public boolean insert(Insertable insertable)
    {
        try(Connection connection = dataSource.getConnection())
        {
            try(PreparedStatement statement = connection.prepareStatement(insertable.getStatement()))
            {
                insertable.setParameters(statement);
                try
                {
                    statement.execute();
                }
                catch(SQLException ex)
                {
                    getLogger().info("[Arcadion] ERROR Executing statement: "+ex.getMessage());
                    return false;
                }
            }
            catch(SQLException ex)
            {
                getLogger().info("[Arcadion] ERROR Preparing statement: "+ex.getMessage());
                return false;
            } //Try with resources closes the statement when its over
        } //Try with resources closes the connection when its over
        catch(SQLException ex)
        {
            getLogger().info("[Arcadion] ERROR Acquiring connection: "+ex.getMessage());
            return false;
        }
        return true;
    }

    public void queueAsyncSelectable(Selectable selectable)
    {
        if(selectable != null)
            asyncSelectables.offer(selectable);
    }

    public boolean select(Selectable selectable)
    {
        try(Connection connection = dataSource.getConnection())
        {
            try(PreparedStatement statement = connection.prepareStatement(selectable.getQuery()))
            {
                selectable.setParameters(statement);
                try
                {
                    ResultSet set = statement.executeQuery();

                    selectable.receiveResult(set);
                    set.close();

                    selectable.callBack();
                }
                catch(SQLException ex)
                {
                    getLogger().info("ERROR Executing query statement: "+ex.getMessage());
                    return false;
                }
            }
            catch(SQLException ex)
            {
                getLogger().info("ERROR Preparing query statement: "+ex.getMessage());
                return false;
            } //Try with resources closes the statement when its over
        } //Try with resources closes the connection when its over
        catch(SQLException ex)
        {
            getLogger().info("ERROR Acquiring query connection: "+ex.getMessage());
            return false;
        }
        return true;
    }

    LinkedTransferQueue<Insertable> getInsertableQueue()
    {
        return asyncInsertables;
    }

    LinkedTransferQueue<Selectable> getSelectableQueue()
    {
        return asyncSelectables;
    }

    HikariDataSource getDataSource()
    {
        return dataSource;
    }

    public boolean executeCommand(String command)
    {
        try(Connection connection = dataSource.getConnection())
        {
            try(PreparedStatement statement = connection.prepareStatement(command))
            {
                return statement.execute();
            }
            catch(SQLException ex)
            {
                getLogger().info("ERROR Preparing command statement: "+ex.getMessage());
                return false;
            }
        }
        catch(SQLException ex)
        {
            getLogger().info("ERROR Acquiring command connection: "+ex.getMessage());
            return false;
        }
    }

    public Connection getConnection() throws SQLException
    {
        if(!isActive())
            return null;
        return dataSource.getConnection();
    }
}
