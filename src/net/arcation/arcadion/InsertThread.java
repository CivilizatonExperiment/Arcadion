package net.arcation.arcadion;

import net.arcation.arcadion.interfaces.Insertable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/*
Created by Mr_Little_Kitty on 5/7/2015
*/
class InsertThread extends Thread implements DisableableThread
{
    private Arcadion arcadion;
    private boolean enabled;

    public InsertThread(Arcadion arcadion)
    {
        this.arcadion = arcadion;
        enabled = true;

    }

    @Override
    public void run()
    {
        while (enabled)
        {
            Insertable next = null;
            try
            {
                next = arcadion.getInsertableQueue().take();
            }
            catch (InterruptedException ex)
            {
                arcadion.getLogger().info("ERROR Thread interrupted while getting Insertable: " + ex.getMessage());
                continue;
            }

            if(next != null)
            {
                try (Connection connection = arcadion.getDataSource().getConnection())
                {
                    try (PreparedStatement statement = connection.prepareStatement(next.getStatement()))
                    {
                        next.setParameters(statement);
                        try
                        {
                            statement.execute();
                        }
                        catch (SQLException ex)
                        {
                            arcadion.getLogger().info("ERROR Executing statement: " + ex.getMessage());
                            continue;
                        }
                    }
                    catch (SQLException ex)
                    {
                        arcadion.getLogger().info("ERROR Preparing statement: " + ex.getMessage());
                        continue;
                    } //Try with resources closes the statement when its over
                } //Try with resources closes the connection when its over
                catch (SQLException ex)
                {
                    arcadion.getLogger().info("ERROR Acquiring connection: " + ex.getMessage());
                    continue;
                }
            }
        }
    }

    public void disable()
    {
        enabled = false;
    }
}
