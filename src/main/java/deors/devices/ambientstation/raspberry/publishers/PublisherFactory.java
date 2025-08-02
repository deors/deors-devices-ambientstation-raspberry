package deors.devices.ambientstation.raspberry.publishers;

import java.lang.reflect.InvocationTargetException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PublisherFactory {

    private Logger logger = Logger.getLogger(PublisherFactory.class.getName());

    private PublisherFactory() {
    }

    public static PublisherFactory getInstance() {
        return PublisherFactoryHolder.INSTANCE;
    }

    private static class PublisherFactoryHolder {

        private static final PublisherFactory INSTANCE = new PublisherFactory();
    }

    public Publisher getPublisher(String providerClass) {
        Publisher result = null;
        try {
            Class<?> provider = Class.forName(providerClass);
            result = (Publisher) provider.getDeclaredConstructor().newInstance();
        } catch (InvocationTargetException | IllegalAccessException | InstantiationException
                 | NoSuchMethodException | ClassNotFoundException ex) {
            logger.log(Level.SEVERE, "unable to instantiate or initialize the publisher object", ex);
        }
        return result;
    }
}
