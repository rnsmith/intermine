package org.flymine.sql.query;

/**
 * A representation of a field in a table.
 *
 * @author Andrew Varley
 */
public class Field extends AbstractValue
{
    protected String name;
    protected Table table;

    /**
     * Constructor for this Field object.
     *
     * @param name the name of the field, as the database knows it
     * @param table the name of the table to which the field belongs, as the database knows it
     */
    public Field(String name, Table table) {
        if (name == null) {
            throw (new NullPointerException("Field names cannot be null"));
        }
        if (table == null) {
            throw (new NullPointerException("Cannot accept null values for table"));
        }
        this.name = name;
        this.table = table;
    }

    /**
     * Returns a String representation of this Field object, suitable for forming part of an SQL
     * query.
     *
     * @return the String representation
     */
    public String getSQLString() {
        String tableName = (table.getAlias() != null) ? table.getAlias() : table.getName();
        return tableName + "." + name;
    }

    /**
     * Overrides Object.equals().
     *
     * @param obj an Object to compare to
     * @return true if the object is of the same class, and with the same name
     */
    public boolean equals(Object obj) {
        if (obj instanceof Field) {
            Field objField = (Field) obj;
            return name.equals(objField.name)
                && table.equals(objField.table);
        }
        return false;
    }

    /**
     * Overrides Object.hashcode().
     *
     * @return an arbitrary integer based on the name of the Table
     */
    public int hashCode() {
        return name.hashCode() + table.hashCode();
    }

    /**
     * Compare this Field to another AbstractValue, including only the table alias.
     *
     * @param obj an AbstractField to compare to
     * @return true if the object is of the same class, and with the same value
     */

    public boolean equalsTableOnlyAlias(AbstractValue obj) {
        if (obj instanceof Field) {
            Field objField = (Field) obj;
            return name.equals(objField.name) && table.equalsOnlyAlias(objField.table);
        }
        return false;
    }
}
