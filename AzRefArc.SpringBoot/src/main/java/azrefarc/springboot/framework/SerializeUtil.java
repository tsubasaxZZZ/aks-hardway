package azrefarc.springboot.framework;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Base64;

public class SerializeUtil {

	public static String SerializeToBase64String(Serializable objToSerialize)
	{
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try
        {
	        ObjectOutputStream oos = new ObjectOutputStream(baos);
	        oos.writeObject(objToSerialize);
	        oos.close();
        }
        catch (IOException ex)
        {
        	throw new RuntimeException("", ex);
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray()); 	
    }
	
	@SuppressWarnings("unchecked")
	public static <T extends Serializable> T DeserializeFromBase64String(String base64StringToDeserialize, Class<T> type)
	{
        byte[] data = Base64.getDecoder().decode(base64StringToDeserialize);
        try
        {
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data) );
            Object o = ois.readObject();
            ois.close();
            return (T)o;
        }
        catch (IOException ex)
        {
        	throw new RuntimeException("", ex);
        }
        catch (ClassNotFoundException fe)
        {
        	throw new RuntimeException("", fe);
        }
    }
}
