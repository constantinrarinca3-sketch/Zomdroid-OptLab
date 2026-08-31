package zombie.core.opengl;
public final class ShaderPrograms { private static final ShaderPrograms I=new ShaderPrograms(); public static ShaderPrograms getInstance(){return I;} public ShaderProgram getProgramByID(int id){return new ShaderProgram();} }
