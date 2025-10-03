package com.initialone.jhumanify;

import com.initialone.jhumanify.commands.*;
import picocli.CommandLine;

@CommandLine.Command(
        name = "jhumanify",
        mixinStandardHelpOptions = true,
        version = "0.2.0",
        subcommands = {
                DecompileCmd.class,
                AnalyzeCmd.class,
                SuggestCmd.class,
                ApplyCmd.class,
                HumanifyCmd.class,
                AnnotateCmd.class
        })
public class Main implements Runnable {
    public void run() { System.out.println("Use a subcommand. Try --help."); }
    public static void main(String[] args) {
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }
}