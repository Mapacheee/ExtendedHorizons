package me.mapacheee.extendedhorizons.commands;

import org.incendo.cloud.annotations.Command;

import com.google.inject.Inject;
import com.thewinterframework.command.CommandComponent;
import com.thewinterframework.service.ReloadServiceManager;

@CommandComponent
public class EhCommands {

    private final ReloadServiceManager reloadServiceManager;

    @Inject
    public EhCommands(ReloadServiceManager reloadServiceManager) {
        this.reloadServiceManager = reloadServiceManager;
    }

    @Command("eh reload")
    public void reloadCommand() {
        reloadServiceManager.reload();
    }
    
}
