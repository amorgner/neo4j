/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.configuration.docs;

import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.neo4j.function.Functions;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.graphdb.factory.Description;
import org.neo4j.kernel.configuration.Group;
import org.neo4j.kernel.configuration.Internal;
import org.neo4j.kernel.configuration.Obsoleted;

/**
 * A meta description of a settings class, used to generate documentation.
 */
public class SettingsDescription
{
    /**
     * Create a description of a given class.
     */
    public static SettingsDescription describe( Class<?> settingClass ) throws Exception
    {
        String classDescription = settingClass.isAnnotationPresent( Description.class )
              ? settingClass.getAnnotation( Description.class ).value()
              : "List of configuration settings";
        Object instance = null;

        for(Class<?> cls = settingClass; cls != null; cls = cls.getSuperclass() )
        {
            if( cls.isAnnotationPresent( Group.class ) )
            {
                // Group classes are special, we need to instantiate them to read their
                // configuration, this is how the group config DSL works
                instance = settingClass.getConstructor( String.class ).newInstance( "{key}" );
                break;
            }
        }

        List<SettingDescription> settings = new LinkedList<>();
        for ( Field field : settingClass.getFields() )
        {
            fieldAsSetting( settingClass, instance, field ).ifPresent( (setting) -> {
                String name = setting.name();
                String description = field.getAnnotation( Description.class ).value();
                String validationMessage = setting.toString();
                String defaultValue = null;
                String mandatoryMessage = null;

                String deprecationMessage = field.isAnnotationPresent( Obsoleted.class )
                                            ? field.getAnnotation( Obsoleted.class ).value()
                                            : field.isAnnotationPresent( Deprecated.class )
                                              ? "The " + name + " configuration setting has been deprecated."
                                              : null;
                try
                {
                    Object rawDefault = setting.apply( Functions.<String,String>nullFunction() );
                    defaultValue = rawDefault != null ? rawDefault.toString() : null;
                }
                catch ( IllegalArgumentException iae )
                {
                    if ( iae.toString().contains( "mandatory" ) )
                    {
                        mandatoryMessage = "The " + name + " configuration setting is mandatory.";
                    }
                }

                settings.add( new SettingDescription(
                        name, description,
                        mandatoryMessage,
                        deprecationMessage,
                        validationMessage,
                        defaultValue,
                        deprecationMessage != null,
                        mandatoryMessage != null,
                        defaultValue != null
                ));
            });
        }

        return new SettingsDescription(
                // Nested classes have `$` in the name, which is an asciidoc keyword
                settingClass.getName().replace( "$", "-" ),
                classDescription,
                settings );
    }

    private static Optional<Setting<?>> fieldAsSetting( Class<?> settingClass, Object instance, Field field )
    {
        Setting<?> setting;
        try
        {
            setting = (Setting<?>) field.get( instance );
        }
        catch ( Exception e )
        {
            return Optional.empty();
        }

        if( field.isAnnotationPresent( Internal.class ) )
        {
            return Optional.empty();
        }

        if( !field.isAnnotationPresent( Description.class ))
        {
            throw new RuntimeException( String.format(
                    "Public setting `%s` is missing description in %s.",
                    setting.name(), settingClass.getName() ) );
        }
        return Optional.of(setting);
    }

    private final String name;
    private final String description;
    private final List<SettingDescription> settings;

    public SettingsDescription( String name, String description, List<SettingDescription> settings )
    {
        this.name = name;
        this.description = description;
        this.settings = settings;
    }

    public Stream<SettingDescription> settings()
    {
        return settings.stream().sorted( (a,b) -> a.name().compareTo( b.name() ) );
    }

    public String id()
    {
        return "config-" + name();
    }

    public String description()
    {
        return description;
    }

    public String name()
    {
        return name;
    }
}
