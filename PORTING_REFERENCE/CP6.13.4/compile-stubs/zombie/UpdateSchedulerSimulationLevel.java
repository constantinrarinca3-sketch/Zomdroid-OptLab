package zombie; public enum UpdateSchedulerSimulationLevel { SIXTEENTH,EIGHTH,QUARTER,HALF,FULL; public int getFrameMod(){return 1;} public int getUpdateOrderIndex(){return ordinal();} }
