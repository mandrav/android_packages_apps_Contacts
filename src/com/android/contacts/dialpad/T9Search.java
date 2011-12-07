/**
 * 
 */
package com.android.contacts.dialpad;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.provider.MediaStore.Images.Media;

/**
 * @author shade,Danesh
 *
 */
class T9Search {

    //Contact name queries
    private final static String LOWER = "LOWER(" + Contacts.DISPLAY_NAME + ")";
    private final static String PEOPLE_QUERY = 
            "(" + LOWER + " GLOB ?) AND " + Contacts.HAS_PHONE_NUMBER + " = 1";
    private static final String[] PEOPLE_PROJECTION = 
            new String[] { Contacts._ID, Contacts.DISPLAY_NAME, Contacts.PHOTO_THUMBNAIL_URI};
    private static final String PEOPLE_SORT = Contacts.DISPLAY_NAME + " COLLATE LOCALIZED ASC"; 
    private static final String[] PHONE_PROJECTION = 
            new String[] { Phone._ID, Contacts.DISPLAY_NAME, Phone.NUMBER, Phone.IS_SUPER_PRIMARY, Phone.PHOTO_THUMBNAIL_URI };
    //Contact number queries
    private static final String PHONE_ID_QUERY = Phone.CONTACT_ID + " = ?";
    private static final String PHONE_QUERY = Phone.NORMALIZED_NUMBER + " GLOB ? OR " + Phone.NUMBER + " GLOB ?";
    private static final String PHONE_QUERY_SORT = Phone.IS_SUPER_PRIMARY + " desc";
    //List sort modes
    private static final int NAME_FIRST = 1;
    private static final int NUMBER_FIRST = 2;
    private static final int DIRECT_NUMBER = 3;
    private Context mContext;
    private int mSortMode;

    public T9Search(Context context) {
        mContext = context;
    }

    public class T9SearchResult {
        private final ArrayList<ContactItem> mResults;
        private ContactItem mTopContact;
        public T9SearchResult (ArrayList<ContactItem> results) {
            mTopContact = new ContactItem();
            mTopContact.name = results.get(0).name;
            mTopContact.number = results.get(0).number;
            mTopContact.photoUri = results.get(0).photoUri;
            results.remove(0);
            this.mResults = results;
        }
        public int getNumResults() {
            return mResults.size()+1;
        }
        public String getTopName() {
            return mTopContact.name;
        }
        public String getTopNumber() {
            return mTopContact.number;
        }
        public Bitmap getTopPhoto() {
            return mTopContact.photoUri;
        }
        public ArrayList<ContactItem> getResults() {
            return mResults;
        }
    }

    protected static class ContactItem {
        Bitmap photoUri;
        String name;
        String number;
        int matchId;
    }

    public T9SearchResult search(String number) {
        number=number.replaceAll( "[^\\d]", "" );
        T9SearchResult result = null;
        ArrayList<ContactItem> nameResults = new ArrayList<ContactItem>();
        ArrayList<ContactItem> numberResults = new ArrayList<ContactItem>();
        Set<ContactItem> allResults = new LinkedHashSet<ContactItem>();
        mSortMode = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(mContext).getString("t9_sort", "1"));

        Cursor c = searchPhones(number);
        try {
            while (c.moveToNext()) {
                ContactItem temp = new ContactItem();
                temp.name = c.getString(1);
                temp.number = c.getString(2);
                temp.matchId = temp.number.replaceAll( "[^\\d]", "" ).indexOf(number);
                if (c.getString(4)!=null)
                    temp.photoUri = Media.getBitmap(mContext.getContentResolver(), Uri.parse(c.getString(4)));
                numberResults.add(temp);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            c.close();
        }

        c = searchContacts(number);
        try {
            while (c.moveToNext()) {
                ContactItem temp = new ContactItem();
                temp.name = c.getString(1);
                temp.number = getBestPhone(c.getString(0));
                temp.matchId = getNameMatchId(temp.name,number);
                if (c.getString(2)!=null)
                    temp.photoUri = Media.getBitmap(mContext.getContentResolver(), Uri.parse(c.getString(2)));
                nameResults.add(temp);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            c.close();
        }
        Collections.sort(nameResults, new CustomComparator());
        Collections.sort(numberResults, new CustomComparator());
        if (nameResults.size() > 0 || numberResults.size() > 0) {
            switch (mSortMode) {
            case NAME_FIRST:
                allResults.addAll(nameResults);
                allResults.addAll(numberResults);
                break;
            case NUMBER_FIRST:
            case DIRECT_NUMBER:
                allResults.addAll(numberResults);
                allResults.addAll(nameResults);
            }
            return new T9SearchResult(new ArrayList<ContactItem>(allResults));
        }
        return result;
    }

    public class CustomComparator implements Comparator<ContactItem> {
        @Override
        public int compare(ContactItem lhs, ContactItem rhs) {
            return Integer.compare(lhs.matchId,rhs.matchId);
        }
    }

    private int getNameMatchId(String name, String input) {
        Pattern pattern = Pattern.compile(buildT9ContactQuery(input),Pattern.CASE_INSENSITIVE);
        Matcher m = pattern.matcher(name);
        if (m.find()){
            return m.start();
        }else{
            return name.length();
        }
    }

    private String getBestPhone(String contactId) {
        String phone = null;
        Cursor c = mContext.getContentResolver().query(Phone.CONTENT_URI, 
                PHONE_PROJECTION,
                PHONE_ID_QUERY,
                new String[] { contactId }, 
                PHONE_QUERY_SORT);
        try {
            if (c.moveToFirst()) {
                phone = c.getString(2);
            }
        } finally {
            c.close();
        }
        return phone;
    }

    private Cursor searchPhones(String number) {
        String query = null;
        if (mSortMode==DIRECT_NUMBER){
            Uri contactUri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(number));
            return mContext.getContentResolver().query(contactUri, PHONE_PROJECTION, null, null, PHONE_QUERY_SORT);
        }else{
            query = "*" + number + "*";
            return mContext.getContentResolver().query(Phone.CONTENT_URI,
                    PHONE_PROJECTION,
                    PHONE_QUERY,
                    new String[] {query,query},
                    PHONE_QUERY_SORT);
        }
    }

    private Cursor searchContacts(String number) {
        String matcher = buildT9ContactQuery(number);
        return mContext.getContentResolver().query(Contacts.CONTENT_URI, 
                PEOPLE_PROJECTION,
                PEOPLE_QUERY, 
                new String[] { "*" + matcher + "*" },
                PEOPLE_SORT);
    }

    private String buildT9ContactQuery(String number) {
        StringBuilder sb = new StringBuilder();
        if (number != null) {
            for (int i = 0; i < number.length(); i++) {
                char key = number.charAt(i);
                sb.append(numberToRegexPart(key));
            }
        }
        return sb.toString();
    }

    private String numberToRegexPart(char c) {
        switch (c) {
        case '2':
            return "[2abc]";
        case '3':
            return "[3def]";
        case '4':
            return "[4ghi]";
        case '5':
            return "[5jkl]";
        case '6':
            return "[6mno]";
        case '7':
            return "[7pqrs]";
        case '8':
            return "[8tuv]";
        case '9':
            return "[9wxyz]";
        case '*':
            return "?";
        default:
            return String.valueOf(c);
        }
    }
}